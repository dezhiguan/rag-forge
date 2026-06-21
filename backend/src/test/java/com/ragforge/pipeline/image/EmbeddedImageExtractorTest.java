package com.ragforge.pipeline.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedImageExtractorTest {

  @Test
  void pdfExtractorExtractsImagesWithPageAndContext(@TempDir Path tempDir) throws IOException {
    Path pdf = tempDir.resolve("with-images.pdf");
    createPdfWithImages(pdf, 3);

    List<ExtractedImage> images = new PdfEmbeddedImageExtractor().extract(pdf, "application/pdf");

    assertEquals(3, images.size());
    for (ExtractedImage image : images) {
      assertEquals("image/png", image.getContentType());
      assertEquals(1, image.getPageNo());
      assertNotNull(image.getBytes());
      assertTrue(image.getBytes().length > 0);
      assertFalse(image.getSurroundingText().isBlank());
      assertTrue(image.getSurroundingText().contains("Architecture"));
    }
  }

  @Test
  void docxExtractorExtractsImagesWithContext(@TempDir Path tempDir) throws IOException {
    Path docx = tempDir.resolve("with-images.docx");
    createDocxWithImages(docx, 2);

    List<ExtractedImage> images =
        new DocxEmbeddedImageExtractor()
            .extract(docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    assertEquals(2, images.size());
    for (int i = 0; i < images.size(); i++) {
      ExtractedImage image = images.get(i);
      assertEquals("image/png", image.getContentType());
      assertEquals(i, image.getFigureIndex());
      assertNotNull(image.getBytes());
      assertTrue(image.getBytes().length > 0);
      assertTrue(image.getSurroundingText().contains("DOCX context"));
    }
  }

  private static void createPdfWithImages(Path target, int count) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 12);
        stream.newLineAtOffset(50, 730);
        stream.showText("Architecture figure context for embedded image extraction.");
        stream.endText();

        for (int i = 0; i < count; i++) {
          var image = LosslessFactory.createFromImage(document, sampleImage(i));
          stream.drawImage(image, 50 + i * 120, 560, 80, 80);
        }
      }

      document.save(target.toFile());
    }
  }

  private static void createDocxWithImages(Path target, int count) throws IOException {
    try (XWPFDocument document = new XWPFDocument()) {
      document.createParagraph().createRun().setText("DOCX context before embedded figures.");
      for (int i = 0; i < count; i++) {
        byte[] png = sampleImageBytes(i);
        document
            .createParagraph()
            .createRun()
            .addPicture(
                new ByteArrayInputStream(png),
                org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                "figure-" + i + ".png",
                Units.toEMU(64),
                Units.toEMU(64));
      }
      try (var out = Files.newOutputStream(target)) {
        document.write(out);
      }
    } catch (Exception e) {
      if (e instanceof IOException io) {
        throw io;
      }
      throw new IOException(e);
    }
  }

  private static byte[] sampleImageBytes(int seed) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(sampleImage(seed), "png", out);
    return out.toByteArray();
  }

  private static BufferedImage sampleImage(int seed) {
    BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(seed % 2 == 0 ? Color.BLUE : Color.ORANGE);
    graphics.fillRect(0, 0, 80, 80);
    graphics.setColor(Color.WHITE);
    graphics.fillOval(20, 20, 40, 40);
    graphics.dispose();
    return image;
  }
}
