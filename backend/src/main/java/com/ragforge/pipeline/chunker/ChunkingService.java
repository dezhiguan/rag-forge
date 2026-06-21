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
    DocumentMeta meta = buildMeta(doc, kb);
    CleanedText cleanedText = new CleanedText(text == null ? "" : text);

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
        profile.setDefaultStrategy("RECURSIVE");
      }
      if (profile.getFallbackChain() == null || profile.getFallbackChain().isEmpty()) {
        profile.setFallbackChain(List.of(profile.getDefaultStrategy(), "FIXED_WINDOW"));
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

  private static DocumentMeta buildMeta(Document doc, KnowledgeBase kb) {
    DocumentMeta meta = new DocumentMeta();
    if (doc != null) {
      meta.setDocId(doc.getId());
      meta.setKbId(doc.getKbId());
      meta.setFilename(doc.getFilename());
      meta.setContentType(doc.getFileType());
    } else if (kb != null) {
      meta.setKbId(kb.getId());
    }
    return meta;
  }

  private static void normalizeChunks(List<Chunk> chunks, String strategyName) {
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      chunk.setSeq(i);
      if (chunk.getChunkParamsJson() == null) {
        chunk.setChunkParamsJson(new LinkedHashMap<>());
      }
      chunk.getChunkParamsJson().put("strategy", strategyName);
    }
  }

  private static String normalizeName(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
