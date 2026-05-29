package com.ragforge.search;

import com.pgvector.PGvector;
import com.ragforge.pipeline.embedder.EmbeddingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorSearchService {

  private final EmbeddingService embedder;
  private final JdbcTemplate jdbcTemplate;

  public List<SearchResult> search(String query, List<Long> kbIds, List<Long> docIds, int topK) {
    float[] queryVector = embedder.embed(query);
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

    return jdbcTemplate.query(
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
  }
}
