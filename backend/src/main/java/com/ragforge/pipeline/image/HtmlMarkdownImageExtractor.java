package com.ragforge.pipeline.image;

import com.ragforge.common.BizException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class HtmlMarkdownImageExtractor implements EmbeddedImageExtractor {

  private static final Pattern HTML_IMG = Pattern.compile("<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern MD_IMG = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)");

  // 外部图片拉取必须有超时，避免云上 worker 出网受限时挂死整条解析链路。
  private final RestTemplate restTemplate =
      new RestTemplateBuilder()
          .setConnectTimeout(Duration.ofSeconds(5))
          .setReadTimeout(Duration.ofSeconds(10))
          .build();

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
      log.info("HtmlMarkdownImageExtractor.extract called: filePath={} contentType={}", filePath.getFileName(), contentType);
      List<ExtractedImage> images = new ArrayList<>();
      collect(source, HTML_IMG, images);
      collect(source, MD_IMG, images);
      log.info("HtmlMarkdownImageExtractor.extract completed: filePath={} images={}", filePath.getFileName(), images.size());
      return images;
    } catch (Exception e) {
      throw new BizException("HTML/Markdown 嵌入图提取失败: " + e.getMessage());
    }
  }

  /**
   * 一遍扫描同时完成两件事：
   * 1) 抽出所有内嵌图（含 data-URI / http(s) 外链）
   * 2) 把 &lt;img&gt; 和 markdown ![]() 在原位替换成自定义占位符 ![image N](rfimg://N)
   *
   * <p>占位符在 TEXT chunk 里保留下来作为图文位置锚点：前端拿到 chunk 后按
   * rfimg://N 把 N 映射回当前文档对应 figureIndex 的 IMAGE chunk 的 presigned URL
   * 实现 inline 渲染。LLM 答案生成也能引用 "参见 image 0"。
   *
   * <p>替换按出现位置排序（不是先 HTML 再 MD 两轮）→ figureIndex 自然按文档阅读顺序编号。
   */
  public RewriteResult extractWithPlaceholders(Path filePath, String contentType) {
    try {
      String source = Files.readString(filePath, StandardCharsets.UTF_8);
      List<RawMatch> matches = new ArrayList<>();
      Matcher htmlM = HTML_IMG.matcher(source);
      while (htmlM.find()) {
        matches.add(new RawMatch(htmlM.start(), htmlM.end(), htmlM.group(1).trim()));
      }
      Matcher mdM = MD_IMG.matcher(source);
      while (mdM.find()) {
        matches.add(new RawMatch(mdM.start(), mdM.end(), mdM.group(1).trim()));
      }
      matches.sort((a, b) -> Integer.compare(a.start, b.start));

      StringBuilder rewritten = new StringBuilder(source.length());
      List<ExtractedImage> images = new ArrayList<>();
      int last = 0;
      for (RawMatch m : matches) {
        // 处理 HTML_IMG 和 MD_IMG 在源文本里 overlap 的极端情况：只处理第一个匹配
        if (m.start < last) continue;
        rewritten.append(source, last, m.start);
        try {
          ImageBytes img = load(m.src);
          if (img != null && img.bytes().length > 0) {
            int figIdx = images.size();
            images.add(
                new ExtractedImage(
                    img.bytes(),
                    img.contentType(),
                    null,
                    figIdx,
                    surrounding(source, m.start, m.end),
                    null));
            // 用 markdown 图片语法 + 自定义 rfimg:// 协议；这样：
            // - LLM 看到能识别为图引用
            // - 不污染向量（占位符就几十字符）
            // - 前端按 scheme 识别后替换为真实 presigned URL，不会误打 GET 请求
            rewritten
                .append("\n\n![image ")
                .append(figIdx)
                .append("](rfimg://")
                .append(figIdx)
                .append(")\n\n");
          } else {
            // 抽取失败（解码失败 / 空 src）就保留原 <img> / ![](...)，不破坏原文档
            rewritten.append(source, m.start, m.end);
          }
        } catch (Exception e) {
          log.warn(
              "HtmlMarkdownImageExtractor.extractWithPlaceholders skip one image: src={} err={}",
              truncate(m.src),
              e.getMessage());
          rewritten.append(source, m.start, m.end);
        }
        last = m.end;
      }
      rewritten.append(source, last, source.length());
      log.info(
          "HtmlMarkdownImageExtractor rewrite done: filePath={} images={} originalChars={} rewrittenChars={}",
          filePath.getFileName(),
          images.size(),
          source.length(),
          rewritten.length());
      return new RewriteResult(rewritten.toString(), images);
    } catch (Exception e) {
      throw new BizException("HTML/Markdown 嵌入图重写失败: " + e.getMessage());
    }
  }

  public record RewriteResult(String rewrittenText, List<ExtractedImage> images) {}

  private record RawMatch(int start, int end, String src) {}

  private void collect(String source, Pattern pattern, List<ExtractedImage> images) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      String src = matcher.group(1).trim();
      // 单张图失败必须独立隔离：云上 worker 出网受限 / data-URI 损坏 / 外链 404 都
      // 不能让整篇文档零图入库（acc-07 真实复现的回归）。
      try {
        ImageBytes image = load(src);
        if (image == null || image.bytes().length == 0) {
          log.debug("HtmlMarkdownImageExtractor skip empty image: src={}", truncate(src));
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
      } catch (Exception e) {
        log.warn(
            "HtmlMarkdownImageExtractor skip one image (continue with others): src={} err={}",
            truncate(src),
            e.getMessage());
      }
    }
  }

  private static String truncate(String src) {
    if (src == null) return "null";
    return src.length() <= 80 ? src : src.substring(0, 77) + "...";
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
