package com.ragforge.pipeline.image;

import com.ragforge.common.BizException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfEmbeddedImageExtractor implements EmbeddedImageExtractor {

  @Override
  public boolean supports(String contentType) {
    return normalize(contentType).equals("application/pdf") || normalize(contentType).equals("pdf");
  }

  @Override
  public List<ExtractedImage> extract(Path filePath, String contentType) {
    try (PDDocument document = PDDocument.load(filePath.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      List<ExtractedImage> images = new ArrayList<>();
      int figureIndex = 0;
      for (int i = 0; i < document.getNumberOfPages(); i++) {
        PDPage page = document.getPage(i);
        stripper.setStartPage(i + 1);
        stripper.setEndPage(i + 1);
        String pageText = trimTo(stripper.getText(document), 800);
        figureIndex = collectImages(page.getResources(), i + 1, pageText, figureIndex, images);
      }
      return images;
    } catch (IOException e) {
      throw new BizException("PDF 嵌入图提取失败: " + e.getMessage());
    }
  }

  private int collectImages(
      PDResources resources,
      int pageNo,
      String pageText,
      int figureIndex,
      List<ExtractedImage> images)
      throws IOException {
    if (resources == null) {
      return figureIndex;
    }
    Iterator<COSName> names = resources.getXObjectNames().iterator();
    while (names.hasNext()) {
      COSName name = names.next();
      PDXObject object = resources.getXObject(name);
      if (object instanceof PDImageXObject image) {
        images.add(
            new ExtractedImage(
                toPngBytes(image),
                "image/png",
                pageNo,
                figureIndex++,
                pageText,
                extractCaption(pageText)));
      } else if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
        figureIndex = collectImages(form.getResources(), pageNo, pageText, figureIndex, images);
      }
    }
    return figureIndex;
  }

  private static byte[] toPngBytes(PDImageXObject image) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image.getImage(), "png", out);
    return out.toByteArray();
  }

  private static String extractCaption(String pageText) {
    if (pageText == null) {
      return null;
    }
    for (String line : pageText.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.matches("(?i)^(figure|fig\\.|图|表)\\s*\\d+.*")) {
        return trimTo(trimmed, 240);
      }
    }
    return null;
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
