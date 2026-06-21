package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class MarkdownHeadingChunkerStrategy implements ChunkerStrategy {

  @Override
  public String name() {
    return "MARKDOWN_HEADING";
  }

  @Override
  public boolean supports(DocumentMeta meta) {
    String type = meta == null ? "" : String.valueOf(meta.getContentType()).toLowerCase();
    String name = meta == null ? "" : String.valueOf(meta.getFilename()).toLowerCase();
    return type.contains("markdown") || "md".equals(type) || name.endsWith(".md");
  }

  @Override
  public List<Chunk> split(CleanedText text, ChunkParams params) {
    ChunkParams p = params == null ? new ChunkParams() : params;
    int maxLevel = p.getMaxHeadingLevel() == null ? 3 : p.getMaxHeadingLevel();
    List<Chunk> chunks = new ArrayList<>();
    TreeMap<Integer, String> headings = new TreeMap<>();
    StringBuilder current = new StringBuilder();
    String currentPath = "";
    for (String line : value(text).split("\\R", -1)) {
      Heading heading = parseHeading(line);
      if (heading != null && heading.level <= maxLevel) {
        flush(chunks, current, currentPath, p);
        headings.tailMap(heading.level, true).clear();
        headings.put(heading.level, heading.title);
        currentPath = String.join("/", headings.values());
      }
      current.append(line).append('\n');
    }
    flush(chunks, current, currentPath, p);
    return chunks;
  }

  private void flush(List<Chunk> chunks, StringBuilder current, String headingPath, ChunkParams p) {
    String content = current.toString().trim();
    if (content.isEmpty()) {
      current.setLength(0);
      return;
    }
    Chunk chunk = new Chunk(chunks.size(), content, content.length());
    chunk.setHeadingPath(headingPath == null || headingPath.isBlank() ? null : headingPath);
    chunk.setChunkParamsJson(Map.of("chunkSize", p.getChunkSize(), "maxHeadingLevel", p.getMaxHeadingLevel()));
    chunks.add(chunk);
    current.setLength(0);
  }

  private Heading parseHeading(String line) {
    if (line == null || !line.startsWith("#")) {
      return null;
    }
    int level = 0;
    while (level < line.length() && line.charAt(level) == '#') {
      level++;
    }
    if (level == 0 || level > 6 || level >= line.length() || line.charAt(level) != ' ') {
      return null;
    }
    return new Heading(level, line.substring(level + 1).trim());
  }

  private static String value(CleanedText text) {
    return text == null || text.getText() == null ? "" : text.getText();
  }

  private record Heading(int level, String title) {}
}
