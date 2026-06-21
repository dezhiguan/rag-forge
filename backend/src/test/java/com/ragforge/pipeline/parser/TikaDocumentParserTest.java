package com.ragforge.pipeline.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragforge.common.BizException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikaDocumentParserTest {

  private static final String SAMPLE_PDF = "sample-chinese.pdf";
  private static Path samplePdfPath;

  private final TikaDocumentParser parser = new TikaDocumentParser();

  @BeforeAll
  static void prepareSamplePdf() throws IOException {
    var resource = TikaDocumentParserTest.class.getClassLoader().getResource(SAMPLE_PDF);
    if (resource != null) {
      samplePdfPath = Path.of(resource.getPath());
      return;
    }
    Path resourcesDir = Paths.get("src/test/resources");
    Files.createDirectories(resourcesDir);
    samplePdfPath = resourcesDir.resolve(SAMPLE_PDF);
    if (!Files.exists(samplePdfPath)) {
      createChinesePdf(samplePdfPath);
    }
  }

  @Test
  void parseChinesePdf_extractsTextAndPageCount() {
    ParseResult result = parser.parse(samplePdfPath.toAbsolutePath().toString(), "pdf");

    assertNotNull(result.getText());
    assertFalse(result.getText().trim().isEmpty());
    assertTrue(result.getText().contains("你好") || result.getText().contains("RAGForge"));
    assertTrue(result.getParseTimeMs() >= 0);
    assertTrue(result.getPageCount() > 0);
    assertEquals(result.getPageCount(), result.getPageBoundaries().size());
  }

  @Test
  void parseEmptyFile_returnsEmptyText(@TempDir Path tempDir) throws IOException {
    Path empty = tempDir.resolve("empty.md");
    Files.createFile(empty);

    ParseResult result = parser.parse(empty.toAbsolutePath().toString(), "md");

    assertEquals("", result.getText());
    assertEquals(1, result.getPageCount());
  }

  @Test
  void parseScannedPdf_returnsEmptyTextWithoutError(@TempDir Path tempDir) throws IOException {
    Path blankPdf = tempDir.resolve("blank.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(blankPdf.toFile());
    }

    ParseResult result = parser.parse(blankPdf.toAbsolutePath().toString(), "pdf");

    assertTrue(result.getText().trim().isEmpty());
    assertTrue(result.getPageCount() > 0);
  }

  @Test
  void parseUnsupportedExe_throwsBizException(@TempDir Path tempDir) throws IOException {
    Path exe = tempDir.resolve("malware.exe");
    Files.writeString(exe, "MZ");

    BizException ex =
        assertThrows(
            BizException.class, () -> parser.parse(exe.toAbsolutePath().toString(), "exe"));

    assertTrue(ex.getMessage().contains("不支持的文件类型"));
    assertTrue(ex.getMessage().contains("exe"));
  }

  private static void createChinesePdf(Path target) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 12);
        stream.newLineAtOffset(50, 700);
        stream.showText("RAGForge test document");

        PDType0Font chineseFont = loadChineseFont(document);
        if (chineseFont != null) {
          stream.setFont(chineseFont, 14);
          stream.newLineAtOffset(0, -24);
          stream.showText("你好世界，文档解析测试。");
        }
        stream.endText();
      }

      document.save(target.toFile());
    }
  }

  private static PDType0Font loadChineseFont(PDDocument document) {
    String[] fontPaths = {
      "/System/Library/Fonts/PingFang.ttc",
      "/System/Library/Fonts/Supplemental/Songti.ttc",
      "/Library/Fonts/Arial Unicode.ttf",
      "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"
    };
    for (String fontPath : fontPaths) {
      Path path = Path.of(fontPath);
      if (!Files.exists(path)) {
        continue;
      }
      try {
        return PDType0Font.load(document, path.toFile());
      } catch (IOException ignored) {
        // try next font
      }
    }
    return null;
  }
}
