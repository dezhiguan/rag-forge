package com.ragforge.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 按 chunkId 批量解析 IMAGE chunk 的预签名图片 URL。
 *
 * <p>检索链路（VectorSearchService/EsSearchService）不回填 image_key，故各处展示 chunk
 * 缩略图时统一走这里：单条 JOIN 取 image_key + 所属文档 bucket，再 presign。image_key 列在
 * 部分环境可能缺失，捕获异常后降级为空 map，不影响 chunk 文本展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkImageResolver {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final JdbcTemplate jdbcTemplate;
  private final ObjectStorage objectStorage;

  /** @return chunkId -&gt; 预签名图片 URL，仅包含真正有 image_key 且 bucket 可用的 chunk。 */
  public Map<Long, String> presignedUrls(Collection<Long> chunkIds) {
    if (chunkIds == null || chunkIds.isEmpty()) {
      return Map.of();
    }
    List<Long> ids = chunkIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    String sql =
        "SELECT dc.id, dc.image_key, d.storage_bucket "
            + "FROM document_chunks dc JOIN documents d ON d.id = dc.doc_id "
            + "WHERE dc.id IN ("
            + placeholders
            + ")";
    Map<Long, String> urls = new HashMap<>();
    try {
      jdbcTemplate.query(
          sql,
          ids.toArray(),
          rs -> {
            long id = rs.getLong("id");
            String key = rs.getString("image_key");
            String bucket = rs.getString("storage_bucket");
            if (key != null && !key.isBlank() && bucket != null && !bucket.isBlank()) {
              urls.put(id, objectStorage.presignedGet(bucket, key, TTL));
            }
          });
    } catch (Exception e) {
      log.warn("ChunkImageResolver skipped (image_key column likely missing): {}", e.getMessage());
    }
    return urls;
  }
}
