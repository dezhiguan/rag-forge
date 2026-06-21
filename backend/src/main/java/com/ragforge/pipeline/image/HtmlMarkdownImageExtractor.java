package com.ragforge.pipeline.image;

import com.ragforge.common.BizException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class HtmlMarkdownImageExtractor implements EmbeddedImageExtractor {

  private static final Pattern HTML_IMG = Pattern.compile("<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern MD_IMG = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)");

  private final RestTemplate restTemplate = new RestTemplate();

  @Override
  public boolean supports(String contentType) {
    String normalized = normalize(contentType);
    return normalized.equals("text/html")
        || normalized.equals("html")
        || normalized.equals("htm")
        || normalized.equals("text/markdown")
        || normalized.equals("md")
        || normalized.equals("markdown");
  }

  @Override
  public List<ExtractedImage> extract(Path filePath, String contentType) {
    try {
      String source = Files.readString(filePath, StandardCharsets.UTF_8);
      List<ExtractedImage> images = new ArrayList<>();
      collect(source, HTML_IMG, images);
      collect(source, MD_IMG, images);
      return images;
    } catch (Exception e) {
      throw new BizException("HTML/Markdown 嵌入图提取失败: " + e.getMessage());
    }
  }

  private void collect(String source, Pattern pattern, List<ExtractedImage> images) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      String src = matcher.group(1).trim();
      ImageBytes image = load(src);
      if (image == null || image.bytes().length == 0) {
        continue;
      }
      images.add(
          new ExtractedImage(
              image.bytes(),
              image.contentType(),
              null,
              images.size(),
              surrounding(source, matcher.start(), matcher.end()),
              null));
    }
  }

  private ImageBytes load(String src) {
    if (src.startsWith("data:")) {
      int comma = src.indexOf(',');
      int semicolon = src.indexOf(';');
      String type = semicolon > 5 ? src.substring(5, semicolon) : "image/png";
      return new ImageBytes(Base64.getDecoder().decode(src.substring(comma + 1)), type);
    }
    if (src.startsWith("http://") || src.startsWith("https://")) {
      ResponseEntity<byte[]> response = restTemplate.getForEntity(URI.create(src), byte[].class);
      String type = response.getHeaders().getContentType() == null
          ? "image/png"
          : response.getHeaders().getContentType().toString();
      return new ImageBytes(response.getBody() == null ? new byte[0] : response.getBody(), type);
    }
    return null;
  }

  private static String surrounding(String source, int start, int end) {
    String plain = source.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
    int left = Math.max(0, Math.min(start, plain.length()) - 300);
    int right = Math.min(plain.length(), Math.min(end, plain.length()) + 300);
    return plain.substring(left, right).trim();
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    int semicolon = value.indexOf(';');
    String normalized = semicolon >= 0 ? value.substring(0, semicolon) : value;
    return normalized.trim().toLowerCase(Locale.ROOT);
  }

  private record ImageBytes(byte[] bytes, String contentType) {}
}
