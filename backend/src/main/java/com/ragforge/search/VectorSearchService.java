package com.ragforge.search;

import com.ragforge.pipeline.embedder.EmbeddingInput;
import com.ragforge.pipeline.embedder.EmbeddingService;
import com.ragforge.pipeline.embedder.VlEmbeddingClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 向量检索服务（Qdrant 后端）。
 *
 * <p>流程：query → embedding(1024) → Qdrant ANN(按 kb_id/doc_id/chunk_type 过滤) → 拿 chunkId+score → 回 PG
 * 按 chunkId 批量取正文/元数据 → 组装 {@link SearchResult}。向量存储与检索由 {@link QdrantVectorStore}
 * 承载；PG 仍是正文/元数据的唯一事实源。返回结构与调用方保持不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

  private final EmbeddingService embedder;
  private final VlEmbeddingClient vlEmbeddingClient;
  private final JdbcTemplate jdbcTemplate;
  private final QdrantVectorStore qdrantVectorStore;
  private final QueryEmbeddingCache queryEmbeddingCache;

  public List<SearchResult> search(String query, List<Long> kbIds, List<Long> docIds, int topK) {
    return search(query, kbIds, docIds, topK, null);
  }

  public List<SearchResult> search(
      String query,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    requireKbScope(kbIds);
    long start = System.currentTimeMillis();
    long embedStart = System.currentTimeMillis();
    // 命中查询向量缓存则跳过 DashScope embedding（检索链路最大头延迟），vector/hybrid QPS 显著提升。
    float[] queryVector = queryEmbeddingCache.get(query, embedder::embed);
    long embedLatencyMs = System.currentTimeMillis() - embedStart;

    long qStart = System.currentTimeMillis();
    List<QdrantVectorStore.ScoredChunk> hits =
        qdrantVectorStore.search(queryVector, kbIds, docIds, chunkTypes(filter), topK);
    long qLatencyMs = System.currentTimeMillis() - qStart;

    List<SearchResult> results = hydrate(hits);
    log.info(
        "Vector search stage completed: topK={} kbCount={} docFilterCount={} resultCount={} "
            + "embedLatency={}ms qdrantLatency={}ms totalLatency={}ms",
        topK,
        kbIds == null ? 0 : kbIds.size(),
        docIds == null ? 0 : docIds.size(),
        results.size(),
        embedLatencyMs,
        qLatencyMs,
        System.currentTimeMillis() - start);
    return results;
  }

  public List<SearchResult> searchByQueries(
      List<String> queries, List<Long> kbIds, List<Long> docIds, int topK) {
    return searchByQueries(queries, kbIds, docIds, topK, null);
  }

  public List<SearchResult> searchByQueries(
      List<String> queries,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    requireKbScope(kbIds);
    if (queries == null || queries.isEmpty()) {
      return List.of();
    }
    long start = System.currentTimeMillis();
    long embedStart = System.currentTimeMillis();
    List<float[]> vectors = embedder.embedBatch(queries);
    long embedLatencyMs = System.currentTimeMillis() - embedStart;
    if (vectors.size() != queries.size()) {
      throw new IllegalStateException("Embedding 数量与改写查询数量不一致");
    }

    // 多路 query 各自检索，按 chunkId 去重保留最高分。
    List<String> types = chunkTypes(filter);
    Map<Long, Double> bestScore = new LinkedHashMap<>();
    long qStart = System.currentTimeMillis();
    for (float[] vector : vectors) {
      for (QdrantVectorStore.ScoredChunk hit :
          qdrantVectorStore.search(vector, kbIds, docIds, types, topK)) {
        bestScore.merge(hit.chunkId(), hit.score(), Math::max);
      }
    }
    long qLatencyMs = System.currentTimeMillis() - qStart;

    List<QdrantVectorStore.ScoredChunk> merged = new ArrayList<>(bestScore.size());
    bestScore.forEach((cid, score) -> merged.add(new QdrantVectorStore.ScoredChunk(cid, score)));
    merged.sort(Comparator.comparingDouble(QdrantVectorStore.ScoredChunk::score).reversed());
    List<QdrantVectorStore.ScoredChunk> top =
        merged.size() > topK ? merged.subList(0, topK) : merged;

    List<SearchResult> results = hydrate(top);
    log.info(
        "Batch vector search completed: queryCount={} topK={} kbCount={} mergedResults={} "
            + "embedLatency={}ms qdrantLatency={}ms totalLatency={}ms",
        queries.size(),
        topK,
        kbIds == null ? 0 : kbIds.size(),
        results.size(),
        embedLatencyMs,
        qLatencyMs,
        System.currentTimeMillis() - start);
    return results;
  }

  public List<SearchResult> searchImage(
      String query,
      String queryImageBase64,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    requireKbScope(kbIds);
    float[] vector =
        hasText(queryImageBase64)
            ? vlEmbeddingClient
                .embed(List.of(EmbeddingInput.image(decodeImageBase64(queryImageBase64), "image/*")))
                .get(0)
            : vlEmbeddingClient.embed(List.of(EmbeddingInput.text(query == null ? "" : query))).get(0);
    return hydrate(qdrantVectorStore.search(vector, kbIds, docIds, chunkTypes(filter), topK));
  }

  /** 用 Qdrant 命中的 chunkId 批量回 PG 取正文/元数据，保持 Qdrant 的分数与顺序。 */
  private List<SearchResult> hydrate(List<QdrantVectorStore.ScoredChunk> hits) {
    if (hits == null || hits.isEmpty()) {
      return List.of();
    }
    List<Long> ids = new ArrayList<>(hits.size());
    for (QdrantVectorStore.ScoredChunk h : hits) {
      ids.add(h.chunkId());
    }
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT dc.id, dc.content, dc.doc_id, d.filename, dc.chunk_index, dc.chunk_type,
                   dc.chunk_modality, dc.image_key
            FROM document_chunks dc
            JOIN documents d ON dc.doc_id = d.id
            WHERE d.parse_status = 'COMPLETED' AND dc.id IN (
            """);
    sql.append("?,".repeat(ids.size()));
    sql.setLength(sql.length() - 1);
    sql.append(")");

    Map<Long, SearchResult> byId = new java.util.HashMap<>();
    jdbcTemplate.query(
        sql.toString(),
        ps -> {
          for (int i = 0; i < ids.size(); i++) {
            ps.setLong(i + 1, ids.get(i));
          }
        },
        (rs, rowNum) -> {
          SearchResult r = new SearchResult();
          r.setChunkId(rs.getLong("id"));
          r.setContent(rs.getString("content"));
          r.setDocId(rs.getLong("doc_id"));
          r.setFilename(rs.getString("filename"));
          r.setChunkIndex(rs.getInt("chunk_index"));
          r.setChunkType(rs.getString("chunk_type"));
          r.setChunkModality(rs.getString("chunk_modality"));
          r.setImageKey(rs.getString("image_key"));
          byId.put(r.getChunkId(), r);
          return r;
        });

    // 按 Qdrant 的分数顺序输出，回填 vectorScore；PG 中不存在的命中（已删/未完成）跳过。
    List<SearchResult> ordered = new ArrayList<>(hits.size());
    for (QdrantVectorStore.ScoredChunk h : hits) {
      SearchResult r = byId.get(h.chunkId());
      if (r == null) {
        continue;
      }
      r.setVectorScore(h.score());
      ordered.add(r);
    }
    return ordered;
  }

  private static List<String> chunkTypes(com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    if (filter == null || filter.getChunkType() == null || filter.getChunkType().isEmpty()) {
      return null;
    }
    return filter.getChunkType();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static byte[] decodeImageBase64(String raw) {
    String payload = raw == null ? "" : raw.trim();
    int comma = payload.indexOf(',');
    if (payload.startsWith("data:") && comma >= 0) {
      payload = payload.substring(comma + 1);
    }
    return Base64.getDecoder().decode(payload);
  }

  private static void requireKbScope(List<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      throw new IllegalStateException("Search requires non-empty kbIds");
    }
  }
}
