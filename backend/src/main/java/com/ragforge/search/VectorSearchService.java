package com.ragforge.search;

import com.pgvector.PGvector;
import com.ragforge.pipeline.image.ImageEmbeddingClient;
import java.util.Base64;
import com.ragforge.pipeline.embedder.EmbeddingService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

  private final EmbeddingService embedder;
  private final ImageEmbeddingClient imageEmbeddingClient;
  private final JdbcTemplate jdbcTemplate;

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
    float[] queryVector = embedder.embed(query);
    long embedLatencyMs = System.currentTimeMillis() - embedStart;
    PGvector pgVector = new PGvector(queryVector);

    StringBuilder sql =
        new StringBuilder(
            """
            SELECT dc.id, dc.content, dc.doc_id, d.filename, dc.chunk_index, dc.chunk_type,
                   dc.chunk_modality, dc.image_key,
                   1 - (dc.content_vector <=> ?::vector) AS similarity
            FROM document_chunks dc
            JOIN documents d ON dc.doc_id = d.id
            WHERE dc.content_vector IS NOT NULL
              AND d.parse_status = 'COMPLETED'
            """);

    if (kbIds != null && !kbIds.isEmpty()) {
      sql.append(" AND dc.kb_id IN (");
      sql.append("?,".repeat(kbIds.size()));
      sql.setLength(sql.length() - 1);
      sql.append(")");
    }

    if (docIds != null && !docIds.isEmpty()) {
      sql.append(" AND dc.doc_id IN (");
      sql.append("?,".repeat(docIds.size()));
      sql.setLength(sql.length() - 1);
      sql.append(")");
    }

    if (filter != null
        && filter.getChunkType() != null
        && !filter.getChunkType().isEmpty()) {
      sql.append(" AND dc.chunk_type = ANY(?)");
    }

    sql.append(" ORDER BY dc.content_vector <=> ?::vector LIMIT ?");

    long dbStart = System.currentTimeMillis();
    List<SearchResult> results =
        jdbcTemplate.query(
            sql.toString(),
            ps -> {
              int idx = 1;
              ps.setObject(idx++, pgVector);
              if (kbIds != null) {
                for (Long kbId : kbIds) {
                  ps.setLong(idx++, kbId);
                }
              }
              if (docIds != null) {
                for (Long docId : docIds) {
                  ps.setLong(idx++, docId);
                }
              }
              if (filter != null
                  && filter.getChunkType() != null
                  && !filter.getChunkType().isEmpty()) {
                java.sql.Array arr =
                    ps.getConnection()
                        .createArrayOf("varchar", filter.getChunkType().toArray(new String[0]));
                ps.setArray(idx++, arr);
              }
              ps.setObject(idx++, pgVector);
              ps.setInt(idx, topK);
            },
            (rs, rowNum) -> {
              SearchResult result = new SearchResult();
              result.setChunkId(rs.getLong("id"));
              result.setContent(rs.getString("content"));
              result.setDocId(rs.getLong("doc_id"));
              result.setFilename(rs.getString("filename"));
              result.setChunkIndex(rs.getInt("chunk_index"));
              result.setVectorScore(rs.getDouble("similarity"));
              result.setChunkType(rs.getString("chunk_type"));
              return result;
            });
    long dbLatencyMs = System.currentTimeMillis() - dbStart;
    long totalLatencyMs = System.currentTimeMillis() - start;
    log.info(
        "Vector search stage completed: topK={} kbCount={} docFilterCount={} resultCount={} embedLatency={}ms pgLatency={}ms totalLatency={}ms",
        topK,
        kbIds == null ? 0 : kbIds.size(),
        docIds == null ? 0 : docIds.size(),
        results.size(),
        embedLatencyMs,
        dbLatencyMs,
        totalLatencyMs);
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

    String sql = buildBatchVectorSql(queries.size(), kbIds, docIds, filter);
    long dbStart = System.currentTimeMillis();
    List<SearchResult> rawResults =
        jdbcTemplate.query(
            sql,
            ps -> {
              int idx = 1;
              for (float[] vector : vectors) {
                PGvector pgVector = new PGvector(vector);
                ps.setObject(idx++, pgVector);
                idx = bindFilters(ps, idx, kbIds, docIds, filter);
                ps.setObject(idx++, pgVector);
                ps.setInt(idx++, topK);
              }
            },
            (rs, rowNum) -> mapSearchResult(rs.getLong("id"), rs));

    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    for (SearchResult item : rawResults) {
      if (item.getChunkId() == null) {
        continue;
      }
      SearchResult existing = dedup.get(item.getChunkId());
      if (existing == null || item.getVectorScore() > existing.getVectorScore()) {
        dedup.put(item.getChunkId(), item);
      }
    }
    List<SearchResult> merged = new ArrayList<>(dedup.values());
    merged.sort(Comparator.comparingDouble(SearchResult::getVectorScore).reversed());
    if (merged.size() > topK) {
      merged = new ArrayList<>(merged.subList(0, topK));
    }

    long dbLatencyMs = System.currentTimeMillis() - dbStart;
    log.info(
        "Batch vector search completed: queryCount={} topK={} kbCount={} docFilterCount={} rawResults={} mergedResults={} embedLatency={}ms pgLatency={}ms totalLatency={}ms",
        queries.size(),
        topK,
        kbIds == null ? 0 : kbIds.size(),
        docIds == null ? 0 : docIds.size(),
        rawResults.size(),
        merged.size(),
        embedLatencyMs,
        dbLatencyMs,
        System.currentTimeMillis() - start);
    return merged;
  }

  private String buildBatchVectorSql(
      int queryCount,
      List<Long> kbIds,
      List<Long> docIds,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    StringBuilder sql = new StringBuilder();
    for (int i = 0; i < queryCount; i++) {
      if (i > 0) {
        sql.append(" UNION ALL ");
      }
      sql.append(
          """
          SELECT * FROM (
            SELECT dc.id, dc.content, dc.doc_id, d.filename, dc.chunk_index, dc.chunk_type,
                   dc.chunk_modality, dc.image_key,
                   1 - (dc.content_vector <=> ?::vector) AS similarity
            FROM document_chunks dc
            JOIN documents d ON dc.doc_id = d.id
            WHERE dc.content_vector IS NOT NULL
              AND d.parse_status = 'COMPLETED'
          """);
      appendFilters(sql, kbIds, docIds, filter);
      sql.append(
          """
            ORDER BY dc.content_vector <=> ?::vector
            LIMIT ?
          ) q
          """);
    }
    return sql.toString();
  }

  private void appendFilters(
      StringBuilder sql,
      List<Long> kbIds,
      List<Long> docIds,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    if (kbIds != null && !kbIds.isEmpty()) {
      sql.append(" AND dc.kb_id IN (");
      sql.append("?,".repeat(kbIds.size()));
      sql.setLength(sql.length() - 1);
      sql.append(")");
    }
    if (docIds != null && !docIds.isEmpty()) {
      sql.append(" AND dc.doc_id IN (");
      sql.append("?,".repeat(docIds.size()));
      sql.setLength(sql.length() - 1);
      sql.append(")");
    }
    if (filter != null
        && filter.getChunkType() != null
        && !filter.getChunkType().isEmpty()) {
      sql.append(" AND dc.chunk_type = ANY(?)");
    }
  }

  private int bindFilters(
      java.sql.PreparedStatement ps,
      int idx,
      List<Long> kbIds,
      List<Long> docIds,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter)
      throws java.sql.SQLException {
    if (kbIds != null && !kbIds.isEmpty()) {
      for (Long kbId : kbIds) {
        ps.setLong(idx++, kbId);
      }
    }
    if (docIds != null && !docIds.isEmpty()) {
      for (Long docId : docIds) {
        ps.setLong(idx++, docId);
      }
    }
    if (filter != null
        && filter.getChunkType() != null
        && !filter.getChunkType().isEmpty()) {
      java.sql.Array arr =
          ps.getConnection().createArrayOf("varchar", filter.getChunkType().toArray(new String[0]));
      ps.setArray(idx++, arr);
    }
    return idx;
  }

  private SearchResult mapSearchResult(long chunkId, java.sql.ResultSet rs)
      throws java.sql.SQLException {
    SearchResult result = new SearchResult();
    result.setChunkId(chunkId);
    result.setContent(rs.getString("content"));
    result.setDocId(rs.getLong("doc_id"));
    result.setFilename(rs.getString("filename"));
    result.setChunkIndex(rs.getInt("chunk_index"));
    result.setVectorScore(rs.getDouble("similarity"));
    result.setChunkType(rs.getString("chunk_type"));
    result.setChunkModality(rs.getString("chunk_modality"));
    result.setImageKey(rs.getString("image_key"));
    return result;
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
            ? imageEmbeddingClient.embedImage(decodeImageBase64(queryImageBase64), "image/*")
            : imageEmbeddingClient.embedText(query == null ? "" : query);
    return searchImageByVector(vector, kbIds, docIds, topK, filter);
  }

  private List<SearchResult> searchImageByVector(
      float[] vector,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    PGvector pgVector = new PGvector(vector);
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT dc.id, dc.content, dc.doc_id, d.filename, dc.chunk_index, dc.chunk_type,
                   dc.chunk_modality, dc.image_key,
                   1 - (dc.image_vector <=> ?::vector) AS similarity
            FROM document_chunks dc
            JOIN documents d ON dc.doc_id = d.id
            WHERE dc.image_vector IS NOT NULL
              AND d.parse_status = 'COMPLETED'
            """);
    appendFilters(sql, kbIds, docIds, filter);
    sql.append(" ORDER BY dc.image_vector <=> ?::vector LIMIT ?");
    return jdbcTemplate.query(
        sql.toString(),
        ps -> {
          int idx = 1;
          ps.setObject(idx++, pgVector);
          idx = bindFilters(ps, idx, kbIds, docIds, filter);
          ps.setObject(idx++, pgVector);
          ps.setInt(idx, topK);
        },
        (rs, rowNum) -> mapSearchResult(rs.getLong("id"), rs));
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
