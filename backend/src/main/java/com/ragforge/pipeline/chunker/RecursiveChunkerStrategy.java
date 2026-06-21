package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecursiveChunkerStrategy implements ChunkerStrategy {

  @Override
  public String name() {
    return "RECURSIVE";
  }

  @Override
  public boolean supports(DocumentMeta meta) {
    return true;
  }

  @Override
  public List<Chunk> split(CleanedText text, ChunkParams params) {
    ChunkParams p = params == null ? new ChunkParams() : params;
    List<String> pieces = splitByPriority(value(text), p.getSeparators(), p.getChunkSize());
    List<Chunk> chunks = new ArrayList<>();
    String previous = "";
    for (String piece : pieces) {
      if (piece.isBlank()) {
        continue;
      }
      String content = withOverlap(previous, piece, p.getOverlap());
      Chunk chunk = new Chunk(chunks.size(), content, content.length());
      chunk.setChunkParamsJson(paramsJson(p));
      chunks.add(chunk);
      previous = piece;
    }
    return chunks;
  }

  private List<String> splitByPriority(String text, List<String> separators, int chunkSize) {
    if (text.length() <= chunkSize) {
      return List.of(text);
    }
    List<String> seps = separators == null || separators.isEmpty() ? new ChunkParams().getSeparators() : separators;
    return splitRecursive(text, seps, 0, chunkSize);
  }

  private List<String> splitRecursive(String text, List<String> separators, int level, int chunkSize) {
    if (text.length() <= chunkSize) {
      return List.of(text);
    }
    if (level >= separators.size()) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < text.length(); i += chunkSize) {
        out.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
      }
      return out;
    }
    String sep = separators.get(level);
    String[] rawParts = text.split(java.util.regex.Pattern.quote(sep));
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String raw : rawParts) {
      String part = raw.isEmpty() ? raw : raw + sep;
      if (current.length() + part.length() <= chunkSize) {
        current.append(part);
        continue;
      }
      if (!current.isEmpty()) {
        out.addAll(splitRecursive(current.toString(), separators, level + 1, chunkSize));
        current.setLength(0);
      }
      if (part.length() > chunkSize) {
        out.addAll(splitRecursive(part, separators, level + 1, chunkSize));
      } else {
        current.append(part);
      }
    }
    if (!current.isEmpty()) {
      out.addAll(splitRecursive(current.toString(), separators, level + 1, chunkSize));
    }
    return out;
  }

  private static String withOverlap(String previous, String current, int overlap) {
    if (overlap <= 0 || previous == null || previous.isEmpty()) {
      return current;
    }
    String prefix = previous.substring(Math.max(0, previous.length() - overlap));
    return prefix + current;
  }

  private static String value(CleanedText text) {
    return text == null || text.getText() == null ? "" : text.getText();
  }

  private static Map<String, Object> paramsJson(ChunkParams p) {
    return Map.of(
        "chunkSize", p.getChunkSize(),
        "overlap", p.getOverlap(),
        "separators", p.getSeparators());
  }
}
