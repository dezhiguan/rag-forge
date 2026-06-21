package com.ragforge.pipeline.image;

import com.ragforge.common.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class DocxEmbeddedImageExtractor implements EmbeddedImageExtractor {

  @Override
  public boolean supports(String contentType) {
    String normalized = normalize(contentType);
    return normalized.equals("docx")
        || normalized.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        || normalized.startsWith("application/vnd.openxmlformats-");
  }

  @Override
  public List<ExtractedImage> extract(Path filePath, String contentType) {
    try (InputStream in = Files.newInputStream(filePath);
        XWPFDocument document = new XWPFDocument(in)) {
      String text = document.getParagraphs().stream()
          .map(p -> p.getText() == null ? "" : p.getText())
          .filter(s -> !s.isBlank())
          .limit(20)
          .reduce("", (a, b) -> (a + " " + b).trim());
      List<ExtractedImage> images = new ArrayList<>();
      int index = 0;
      for (XWPFPictureData picture : document.getAllPictures()) {
        String imageType = picture.suggestFileExtension();
        images.add(
            new ExtractedImage(
                picture.getData(),
                contentTypeFor(imageType),
                null,
                index++,
                trimTo(text, 800),
                null));
      }
      return images;
    } catch (IOException e) {
      throw new BizException("DOCX 嵌入图提取失败: " + e.getMessage());
    }
  }

  private static String contentTypeFor(String extension) {
    String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    return switch (ext) {
      case "jpg", "jpeg" -> "image/jpeg";
      case "gif" -> "image/gif";
      case "webp" -> "image/webp";
      default -> "image/png";
    };
  }

  private static String trimTo(String value, int max) {
    if (value == null) {
      return "";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= max ? normalized : normalized.substring(0, max);
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    int semicolon = value.indexOf(';');
    String normalized = semicolon >= 0 ? value.substring(0, semicolon) : value;
    return normalized.trim().toLowerCase(Locale.ROOT);
  }
}
