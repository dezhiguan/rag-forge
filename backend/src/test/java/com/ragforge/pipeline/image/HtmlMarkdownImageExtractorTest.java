package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.common.BizException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlMarkdownImageExtractorTest {

  private final HtmlMarkdownImageExtractor extractor = new HtmlMarkdownImageExtractor();

  @TempDir
  Path tempDir;

  // ---- supports() ----

  @Test
  void supports_htmlContentType_returnsTrue() {
    assertThat(extractor.supports("text/html")).isTrue();
    assertThat(extractor.supports("html")).isTrue();
    assertThat(extractor.supports("htm")).isTrue();
  }

  @Test
  void supports_markdownContentType_returnsTrue() {
    assertThat(extractor.supports("text/markdown")).isTrue();
    assertThat(extractor.supports("md")).isTrue();
    assertThat(extractor.supports("markdown")).isTrue();
  }

  @Test
  void supports_htmlWithCharset_returnsTrue() {
    assertThat(extractor.supports("text/html; charset=utf-8")).isTrue();
  }

  @Test
  void supports_pdfContentType_returnsFalse() {
    assertThat(extractor.supports("application/pdf")).isFalse();
    assertThat(extractor.supports("docx")).isFalse();
    assertThat(extractor.supports(null)).isFalse();
  }

  // ---- extract() with HTML ----

  @Test
  void extract_htmlWithDataUriImage_returnsExtractedImage(@TempDir Path dir) throws Exception {
    String base64Png = Base64.getEncoder().encodeToString(minimalPng());
    String html = "<html><body><img src=\"data:image/png;base64," + base64Png + "\"/><p>Hello</p></body></html>";
    Path file = dir.resolve("test.html");
    Files.writeString(file, html);

    List<ExtractedImage> images = extractor.extract(file, "text/html");

    assertThat(images).hasSize(1);
    assertThat(images.get(0).getContentType()).isEqualTo("image/png");
    assertThat(images.get(0).getFigureIndex()).isEqualTo(0);
  }

  @Test
  void extract_htmlNoImages_returnsEmptyList(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("no-images.html");
    Files.writeString(file, "<html><body><p>No images here</p></body></html>");

    List<ExtractedImage> images = extractor.extract(file, "text/html");

    assertThat(images).isEmpty();
  }

  @Test
  void extract_markdownWithDataUriImage_returnsExtractedImage(@TempDir Path dir) throws Exception {
    String base64Png = Base64.getEncoder().encodeToString(minimalPng());
    String md = "# Title\n\n![Alt text](data:image/png;base64," + base64Png + ")\n\nSome text.";
    Path file = dir.resolve("test.md");
    Files.writeString(file, md);

    List<ExtractedImage> images = extractor.extract(file, "text/markdown");

    assertThat(images).hasSize(1);
    assertThat(images.get(0).getFigureIndex()).isEqualTo(0);
  }

  @Test
  void extract_htmlWithInvalidBase64_skipsAndContinues(@TempDir Path dir) throws Exception {
    String base64Png = Base64.getEncoder().encodeToString(minimalPng());
    String html = """
        <html><body>
          <img src="data:image/png;base64,NOT_VALID_BASE64!!!"/>
          <img src="data:image/png;base64,""" + base64Png + """
        "/>
        </body></html>
        """;
    Path file = dir.resolve("partial.html");
    Files.writeString(file, html);

    // Should skip the invalid one and return the valid one
    List<ExtractedImage> images = extractor.extract(file, "text/html");
    assertThat(images).hasSize(1);
  }

  @Test
  void extract_htmlWithMultipleImages_returnAllInOrder(@TempDir Path dir) throws Exception {
    String b64 = Base64.getEncoder().encodeToString(minimalPng());
    String html = "<html><body>"
        + "<img src=\"data:image/png;base64," + b64 + "\"/>"
        + "<img src=\"data:image/png;base64," + b64 + "\"/>"
        + "<img src=\"data:image/png;base64," + b64 + "\"/>"
        + "</body></html>";
    Path file = dir.resolve("multi.html");
    Files.writeString(file, html);

    List<ExtractedImage> images = extractor.extract(file, "text/html");

    assertThat(images).hasSize(3);
    assertThat(images.get(0).getFigureIndex()).isEqualTo(0);
    assertThat(images.get(1).getFigureIndex()).isEqualTo(1);
    assertThat(images.get(2).getFigureIndex()).isEqualTo(2);
  }

  // ---- extractWithPlaceholders() ----

  @Test
  void extractWithPlaceholders_htmlWithDataUri_replacesWithPlaceholder(@TempDir Path dir) throws Exception {
    String b64 = Base64.getEncoder().encodeToString(minimalPng());
    String html = "<p>Before</p><img src=\"data:image/png;base64," + b64 + "\"/><p>After</p>";
    Path file = dir.resolve("placeholder.html");
    Files.writeString(file, html);

    HtmlMarkdownImageExtractor.RewriteResult result = extractor.extractWithPlaceholders(file, "text/html");

    assertThat(result.images()).hasSize(1);
    assertThat(result.rewrittenText()).contains("rfimg://");
    assertThat(result.rewrittenText()).doesNotContain("data:image/png");
  }

  @Test
  void extractWithPlaceholders_markdownNoImages_returnsOriginalText(@TempDir Path dir) throws Exception {
    String md = "# Heading\n\nJust text, no images.\n";
    Path file = dir.resolve("no-img.md");
    Files.writeString(file, md);

    HtmlMarkdownImageExtractor.RewriteResult result = extractor.extractWithPlaceholders(file, "text/markdown");

    assertThat(result.images()).isEmpty();
    assertThat(result.rewrittenText()).isEqualTo(md);
  }

  /** Minimal 1×1 transparent PNG bytes. */
  private static byte[] minimalPng() {
    return Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
  }
}
