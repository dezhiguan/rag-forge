package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TableAwareChunkerStrategy implements ChunkerStrategy {

  private static final Set<String> SUPPORTED_CONTENT_TYPES =
      Set.of("text/markdown", "text/html", "application/pdf");

  @Override
  public String name() {
    return "TABLE_AWARE";
  }

  @Override
  public boolean supports(DocumentMeta meta) {
    if (meta == null || meta.getContentType() == null) {
      return false;
    }
    String contentType = meta.getContentType().trim().toLowerCase(Locale.ROOT);
    int semicolon = contentType.indexOf(';');
    if (semicolon >= 0) {
      contentType = contentType.substring(0, semicolon).trim();
    }
    return SUPPORTED_CONTENT_TYPES.contains(contentType);
  }

  @Override
  public List<Chunk> split(CleanedText text, ChunkParams params) {
    ChunkParams p = params == null ? new ChunkParams() : params;
    List<Chunk> chunks = new ArrayList<>();
    StringBuilder normal = new StringBuilder();
    StringBuilder table = new StringBuilder();
    for (String line : value(text).split("\\R", -1)) {
      if (looksLikeTableLine(line)) {
        flushNormal(chunks, normal, p);
        if (p.getTablePolicy() == TablePolicy.ROW) {
          add(chunks, line, p);
        } else {
          table.append(line).append('\n');
        }
      } else {
        flushTable(chunks, table, p);
        normal.append(line).append('\n');
      }
    }
    flushNormal(chunks, normal, p);
    flushTable(chunks, table, p);
    return chunks;
  }

  private static boolean looksLikeTableLine(String line) {
    if (line == null) {
      return false;
    }
    String trimmed = line.trim();
    return trimmed.contains("|") && trimmed.chars().filter(ch -> ch == '|').count() >= 2
        || trimmed.split("\\s{2,}").length >= 3;
  }

  private static void flushNormal(List<Chunk> chunks, StringBuilder normal, ChunkParams p) {
    String content = normal.toString().trim();
    normal.setLength(0);
    if (!content.isEmpty()) {
      add(chunks, content, p);
    }
  }

  private static void flushTable(List<Chunk> chunks, StringBuilder table, ChunkParams p) {
    String content = table.toString().trim();
    table.setLength(0);
    if (!content.isEmpty()) {
      add(chunks, content, p);
    }
  }

  private static void add(List<Chunk> chunks, String content, ChunkParams p) {
    Chunk chunk = new Chunk(chunks.size(), content, content.length());
    chunk.setChunkParamsJson(Map.of("tablePolicy", p.getTablePolicy().name(), "chunkSize", p.getChunkSize()));
    chunks.add(chunk);
  }

  private static String value(CleanedText text) {
    return text == null || text.getText() == null ? "" : text.getText();
  }
}
