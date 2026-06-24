package com.ragforge.pipeline.chunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ChunkingService {

  private final List<ChunkerStrategy> strategies;
  private final ObjectMapper objectMapper;

  public ChunkingResult split(Document doc, KnowledgeBase kb, String text) {
    ChunkerProfile profile = resolveProfile(kb);
    applyLegacyKbParams(profile, kb);
    CleanedText cleanedText = new CleanedText(text == null ? "" : text);
    DocumentMeta meta = buildMeta(doc, kb, cleanedText);

    Map<String, ChunkerStrategy> strategyByName =
        strategies.stream()
            .collect(
                Collectors.toMap(
                    strategy -> normalizeName(strategy.name()),
                    strategy -> strategy,
                    (left, right) -> left,
                    LinkedHashMap::new));

    List<String> chain = buildChain(profile);
    for (String strategyName : chain) {
      ChunkerStrategy strategy = strategyByName.get(normalizeName(strategyName));
      if (strategy == null || !strategy.supports(meta)) {
        continue;
      }
      List<Chunk> chunks = strategy.split(cleanedText, profile.getParams());
      if (chunks != null && !chunks.isEmpty()) {
        normalizeChunks(chunks, strategy.name());
        return new ChunkingResult(strategy.name(), profile.getParams(), chunks);
      }
    }

    throw new BizException(500, "NO_CHUNKER_STRATEGY_AVAILABLE");
  }

  /** Run exactly one strategy (no fallback chain) for explicit rechunk requests. */
  public ChunkingResult splitWithStrategy(
      Document doc, KnowledgeBase kb, String text, String strategyName, ChunkParams overrideParams) {
    String normalized = normalizeName(strategyName);
    ChunkerStrategy strategy =
        strategies.stream()
            .filter(item -> normalizeName(item.name()).equals(normalized))
            .findFirst()
            .orElseThrow(() -> new BizException(400, "INVALID_STRATEGY"));

    ChunkerProfile profile = resolveProfile(kb);
    applyLegacyKbParams(profile, kb);
    ChunkParams params = profile.getParams() == null ? new ChunkParams() : profile.getParams();
    if (overrideParams != null) {
      if (overrideParams.getChunkSize() > 0) {
        params.setChunkSize(overrideParams.getChunkSize());
      }
      if (overrideParams.getOverlap() >= 0) {
        params.setOverlap(overrideParams.getOverlap());
      }
    }

    CleanedText cleanedText = new CleanedText(text == null ? "" : text);
    DocumentMeta meta = buildMeta(doc, kb, cleanedText);
    if (!strategy.supports(meta)) {
      if ("SEMANTIC".equals(normalized)) {
        throw new BizException(400, "SEMANTIC_REQUIRES_LONG_TEXT");
      }
      throw new BizException(400, "INVALID_STRATEGY");
    }

    List<Chunk> chunks = strategy.split(cleanedText, params);
    if (chunks == null || chunks.isEmpty()) {
      throw new BizException(500, "NO_CHUNKER_STRATEGY_AVAILABLE");
    }
    normalizeChunks(chunks, strategy.name());
    return new ChunkingResult(strategy.name(), params, chunks);
  }

  public ChunkerProfile resolveProfile(KnowledgeBase kb) {
    if (kb == null || !StringUtils.hasText(kb.getChunkerProfileJson())) {
      return new ChunkerProfile();
    }
    try {
      ChunkerProfile profile = objectMapper.readValue(kb.getChunkerProfileJson(), ChunkerProfile.class);
      if (profile.getParams() == null) {
        profile.setParams(new ChunkParams());
      }
      if (!StringUtils.hasText(profile.getDefaultStrategy())) {
        profile.setDefaultStrategy("MARKDOWN_HEADING");
      }
      if (profile.getFallbackChain() == null || profile.getFallbackChain().isEmpty()) {
        profile.setFallbackChain(List.of("RECURSIVE", "FIXED_WINDOW"));
      }
      return profile;
    } catch (Exception e) {
      throw new BizException(400, "INVALID_CHUNKER_PROFILE_JSON");
    }
  }

  private void applyLegacyKbParams(ChunkerProfile profile, KnowledgeBase kb) {
    if (kb == null || profile.getParams() == null) {
      return;
    }
    if (kb.getChunkSize() != null) {
      profile.getParams().setChunkSize(kb.getChunkSize());
    }
    if (kb.getChunkOverlap() != null) {
      profile.getParams().setOverlap(kb.getChunkOverlap());
    }
  }

  private static List<String> buildChain(ChunkerProfile profile) {
    List<String> chain = new ArrayList<>();
    chain.add(profile.getDefaultStrategy());
    if (profile.getFallbackChain() != null) {
      chain.addAll(profile.getFallbackChain());
    }
    chain.add("FIXED_WINDOW");
    return chain.stream().filter(Objects::nonNull).map(ChunkingService::normalizeName).distinct().toList();
  }

  private static DocumentMeta buildMeta(Document doc, KnowledgeBase kb, CleanedText cleanedText) {
    DocumentMeta meta = new DocumentMeta();
    if (doc != null) {
      meta.setDocId(doc.getId());
      meta.setKbId(doc.getKbId());
      meta.setFilename(doc.getFilename());
      meta.setContentType(doc.getFileType());
    } else if (kb != null) {
      meta.setKbId(kb.getId());
    }
    meta.setTextLength(cleanedText == null || cleanedText.getText() == null ? 0 : cleanedText.getText().length());
    return meta;
  }

  private static void normalizeChunks(List<Chunk> chunks, String strategyName) {
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      chunk.setSeq(i);
      if (chunk.getChunkParamsJson() == null) {
        chunk.setChunkParamsJson(new LinkedHashMap<>());
      } else if (!(chunk.getChunkParamsJson() instanceof LinkedHashMap)) {
        chunk.setChunkParamsJson(new LinkedHashMap<>(chunk.getChunkParamsJson()));
      }
      chunk.getChunkParamsJson().put("strategy", strategyName);
    }
  }

  private static String normalizeName(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
