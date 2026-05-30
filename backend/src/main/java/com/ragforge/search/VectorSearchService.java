package com.ragforge.search;

import com.pgvector.PGvector;
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
  private final JdbcTemplate jdbcTemplate;

  public List<SearchResult> search(String query, List<Long> kbIds, List<Long> docIds, int topK) {
    long start = System.currentTimeMillis();
    long embedStart = System.currentTimeMillis();
    float[] queryVector = embedder.embed(query);
    long embedLatencyMs = System.currentTimeMillis() - embedStart;
    PGvector pgVector = new PGvector(queryVector);

    StringBuilder sql =
        new StringBuilder(
            """
            SELECT dc.id, dc.content, dc.doc_id, d.filename, dc.chunk_index,
                   1 - (dc.content_vector <=> ?::vector) AS similarity
            FROM document_chunks dc
            JOIN documents d ON dc.doc_id = d.id
            WHERE dc.content_vector IS NOT NULL
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

    String sql = buildBatchVectorSql(queries.size(), kbIds, docIds);
    long dbStart = System.currentTimeMillis();
    List<SearchResult> rawResults =
        jdbcTemplate.query(
            sql,
            ps -> {
              int idx = 1;
              for (float[] vector : vectors) {
                PGvector pgVector = new PGvector(vector);
                ps.setObject(idx++, pgVector);
                idx = bindFilters(ps, idx, kbIds, docIds);
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

  private String buildBatchVectorSql(int queryCount, List<Long> kbIds, List<Long> docIds) {
    StringBuilder sql = new StringBuilder();
    for (int i = 0; i < queryCount; i++) {
      if (i > 0) {
        sql.append(" UNION ALL ");
      }
      sql.append(
          """
          SELECT * FROM (
            SELECT dc.id, dc.content, dc.doc_id, d.filename, dc.chunk_index,
                   1 - (dc.content_vector <=> ?::vector) AS similarity
            FROM document_chunks dc
            JOIN documents d ON dc.doc_id = d.id
            WHERE dc.content_vector IS NOT NULL
          """);
      appendFilters(sql, kbIds, docIds);
      sql.append(
          """
            ORDER BY dc.content_vector <=> ?::vector
            LIMIT ?
          ) q
          """);
    }
    return sql.toString();
  }

  private void appendFilters(StringBuilder sql, List<Long> kbIds, List<Long> docIds) {
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
  }

  private int bindFilters(
      java.sql.PreparedStatement ps, int idx, List<Long> kbIds, List<Long> docIds)
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
    return result;
  }
}
