package com.ragforge.pipeline.chunker;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ragforge.pipeline.embedder.EmbeddingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SemanticChunkerStrategy implements ChunkerStrategy {

  private final EmbeddingService embeddingService;
  private final Cache<String, float[]> embeddingCache =
      Caffeine.newBuilder().maximumSize(4096).expireAfterWrite(Duration.ofHours(6)).build();

  @Override
  public String name() {
    return "SEMANTIC";
  }

  @Override
  public boolean supports(DocumentMeta meta) {
    return true;
  }

  @Override
  public List<Chunk> split(CleanedText text, ChunkParams params) {
    ChunkParams p = params == null ? new ChunkParams() : params;
    double threshold = p.getSimThreshold() == null ? 0.65 : p.getSimThreshold();
    List<String> sentences = sentences(value(text));
    if (sentences.isEmpty()) {
      return List.of();
    }
    List<Chunk> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder(sentences.get(0));
    float[] previous = embedding(sentences.get(0));
    for (int i = 1; i < sentences.size(); i++) {
      String sentence = sentences.get(i);
      float[] vector = embedding(sentence);
      boolean shouldMerge =
          cosine(previous, vector) >= threshold && current.length() + sentence.length() <= p.getChunkSize();
      if (shouldMerge) {
        current.append(sentence);
      } else {
        add(chunks, current.toString(), p);
        current.setLength(0);
        current.append(sentence);
      }
      previous = vector;
    }
    add(chunks, current.toString(), p);
    return chunks;
  }

  private float[] embedding(String text) {
    return embeddingCache.get(text, embeddingService::embed);
  }

  private static List<String> sentences(String text) {
    List<String> out = new ArrayList<>();
    for (String item : text.split("(?<=[。！？!?\\.])|\\n+")) {
      if (!item.isBlank()) {
        out.add(item.trim());
      }
    }
    return out;
  }

  private static void add(List<Chunk> chunks, String content, ChunkParams p) {
    Chunk chunk = new Chunk(chunks.size(), content, content.length());
    chunk.setChunkParamsJson(Map.of("chunkSize", p.getChunkSize(), "simThreshold", p.getSimThreshold()));
    chunks.add(chunk);
  }

  private static double cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length == 0 || a.length != b.length) {
      return 0.0;
    }
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return na == 0 || nb == 0 ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }

  private static String value(CleanedText text) {
    return text == null || text.getText() == null ? "" : text.getText();
  }
}
