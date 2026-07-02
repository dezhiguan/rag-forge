package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 结构感知分块:识别中文/通用结构标记(【标题】、一、二、、第 X 章/节、N. 编号,以及 Markdown #),
 * 按小节切分,让"事实卡 / 条款 / 操作手册"这类多小节文档每节各自成块,减少检索/重排时的主题稀释
 * (例如"创始人"埋在多主题密集大块里导致排不上)。
 *
 * <p>关键降级:整篇未识别到任何结构标记时返回空列表,由 ChunkingService 的策略链自动降级到
 * RECURSIVE,因此对无结构的普通文本零影响。
 */
@Component
public class StructuredHeadingChunkerStrategy implements ChunkerStrategy {

  private static final Pattern MD = Pattern.compile("^#{1,6}\\s+\\S");
  private static final Pattern BRACKET = Pattern.compile("^【[^】]{1,40}】");
  private static final Pattern CN_ORDINAL = Pattern.compile("^[一二三四五六七八九十]{1,3}[、.．]\\s*\\S");
  private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千\\d]{1,6}[章节篇条部].{0,20}$");
  private static final Pattern NUM = Pattern.compile("^\\d{1,3}[.、)）]\\s*\\S");

  private final TextChunker textChunker = new TextChunker();

  @Override
  public String name() {
    return "STRUCTURED_HEADING";
  }

  @Override
  public boolean supports(DocumentMeta meta) {
    String type = meta == null ? "" : String.valueOf(meta.getContentType()).toLowerCase();
    String name = meta == null ? "" : String.valueOf(meta.getFilename()).toLowerCase();
    return type.contains("text")
        || type.contains("plain")
        || type.contains("markdown")
        || name.endsWith(".txt")
        || name.endsWith(".md")
        || name.endsWith(".markdown");
  }

  @Override
  public List<Chunk> split(CleanedText text, ChunkParams params) {
    ChunkParams p = params == null ? new ChunkParams() : params;
    int chunkSize = p.getChunkSize() <= 0 ? 500 : p.getChunkSize();
    int overlap = Math.max(0, Math.min(p.getOverlap(), chunkSize - 1));
    String value = text == null || text.getText() == null ? "" : text.getText();

    List<Section> sections = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    String currentHeading = null;
    int headingCount = 0;
    for (String line : value.split("\\R", -1)) {
      if (isHeading(line.trim())) {
        if (current.length() > 0) {
          sections.add(new Section(currentHeading, current.toString()));
        }
        currentHeading = line.trim();
        current.setLength(0);
        current.append(line).append('\n');
        headingCount++;
      } else {
        current.append(line).append('\n');
      }
    }
    if (current.length() > 0) {
      sections.add(new Section(currentHeading, current.toString()));
    }

    // 无结构标记 → 返回空,交由链路降级到 RECURSIVE(不影响无结构文档)。
    if (headingCount == 0) {
      return List.of();
    }

    List<Chunk> chunks = new ArrayList<>();
    for (Section sec : sections) {
      String content = sec.content().trim();
      if (content.isEmpty()) {
        continue;
      }
      if (content.length() <= chunkSize) {
        addChunk(chunks, content, sec.heading(), p);
      } else {
        // 大节按 chunkSize 细分,保留小节标题作为 headingPath。
        for (Chunk sub : textChunker.chunk(content, chunkSize, overlap)) {
          addChunk(chunks, sub.getContent(), sec.heading(), p);
        }
      }
    }
    return chunks;
  }

  private void addChunk(List<Chunk> chunks, String content, String heading, ChunkParams p) {
    Chunk chunk = new Chunk(chunks.size(), content, content.length());
    if (heading != null && !heading.isBlank()) {
      chunk.setHeadingPath(heading);
    }
    chunk.setChunkParamsJson(Map.of("chunkSize", p.getChunkSize(), "strategy", name()));
    chunks.add(chunk);
  }

  private boolean isHeading(String line) {
    if (line == null || line.isEmpty() || line.length() > 60) {
      return false;
    }
    return MD.matcher(line).find()
        || BRACKET.matcher(line).find()
        || CN_ORDINAL.matcher(line).find()
        || CHAPTER.matcher(line).find()
        || NUM.matcher(line).find();
  }

  private record Section(String heading, String content) {}
}
