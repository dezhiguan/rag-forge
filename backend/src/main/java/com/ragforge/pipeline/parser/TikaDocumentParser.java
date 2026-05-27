package com.ragforge.pipeline.parser;

import com.ragforge.common.BizException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

  private static final Set<String> SUPPORTED_TYPES =
      Set.of("pdf", "doc", "docx", "md", "markdown", "html", "htm");

  private final Parser parser = new AutoDetectParser();

  @Override
  public ParseResult parse(String filePath, String fileType) {
    String normalizedType = normalizeFileType(fileType);
    if (!SUPPORTED_TYPES.contains(normalizedType)) {
      throw new BizException("不支持的文件类型：" + fileType);
    }

    File file = new File(filePath);
    if (!file.exists() || !file.isFile()) {
      throw new BizException("文件不存在：" + filePath);
    }

    if (file.length() == 0) {
      int pageCount = "pdf".equals(normalizedType) ? 0 : 1;
      return new ParseResult("", 0L, pageCount);
    }

    long start = System.currentTimeMillis();
    String text = extractText(file);
    long parseTimeMs = System.currentTimeMillis() - start;
    int pageCount = resolvePageCount(filePath, normalizedType);

    if ("pdf".equals(normalizedType) && pageCount > 0 && text.trim().isEmpty()) {
      log.warn("可能为扫描件，无文字层: {}", filePath);
    }

    return new ParseResult(text, parseTimeMs, pageCount);
  }

  private String extractText(File file) {
    StringWriter writer = new StringWriter();
    BodyContentHandler handler = new BodyContentHandler(writer);

    Metadata metadata = new Metadata();
    metadata.set(Metadata.CONTENT_ENCODING, StandardCharsets.UTF_8.name());

    ParseContext context = new ParseContext();
    context.set(Parser.class, parser);

    try (InputStream inputStream = new FileInputStream(file)) {
      parser.parse(inputStream, handler, metadata, context);
      return writer.toString();
    } catch (IOException | SAXException e) {
      throw new BizException("文档解析失败：" + e.getMessage());
    } catch (Exception e) {
      throw new BizException("文档解析失败：" + e.getMessage());
    }
  }

  private int resolvePageCount(String filePath, String fileType) {
    if (!"pdf".equals(fileType)) {
      return 1;
    }
    try (PDDocument document = PDDocument.load(new File(filePath))) {
      return document.getNumberOfPages();
    } catch (IOException e) {
      throw new BizException("文档解析失败：" + e.getMessage());
    }
  }

  private String normalizeFileType(String fileType) {
    if (fileType == null) {
      return "";
    }
    return fileType.toLowerCase(Locale.ROOT);
  }
}
