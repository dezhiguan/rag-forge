package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.entity.Document;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

  @Select(
      """
      SELECT *
      FROM documents
      WHERE kb_id = #{kbId}
        AND external_id = #{externalId}
      ORDER BY id DESC
      LIMIT 1
      FOR UPDATE
      """)
  Document selectByExternalIdForUpdate(
      @Param("kbId") Long kbId, @Param("externalId") String externalId);

  @Select(
      """
      SELECT *
      FROM documents
      WHERE kb_id = #{kbId}
        AND source_url = #{sourceUrl}
        AND external_id IS NULL
      ORDER BY id DESC
      LIMIT 1
      FOR UPDATE
      """)
  Document selectBySourceUrlForUpdate(
      @Param("kbId") Long kbId, @Param("sourceUrl") String sourceUrl);

  @Select(
      """
      SELECT *
      FROM documents
      WHERE kb_id = #{kbId}
        AND content_md5 = #{contentMd5}
        AND external_id IS NULL
        AND source_url IS NULL
      ORDER BY id DESC
      LIMIT 1
      FOR UPDATE
      """)
  Document selectByContentMd5ForUpdate(
      @Param("kbId") Long kbId, @Param("contentMd5") String contentMd5);

  @Select(
      """
      SELECT COUNT(1)
      FROM documents
      WHERE storage_bucket = #{bucket}
        AND file_path = #{storageKey}
      """)
  long countByStorageLocation(
      @Param("bucket") String bucket, @Param("storageKey") String storageKey);

  @Update("UPDATE documents SET parse_status = #{status}, updated_at = NOW() WHERE id = #{id}")
  int updateStatus(@Param("id") Long id, @Param("status") String status);

  @Update(
      """
      UPDATE documents
      SET clean_profile_id = #{profileId},
          clean_report_json = #{reportJson,typeHandler=com.ragforge.mybatis.handler.JsonbStringTypeHandler},
          updated_at = NOW()
      WHERE id = #{id}
      """)
  int updateCleanReport(
      @Param("id") Long id,
      @Param("profileId") Long profileId,
      @Param("reportJson") String reportJson);

  @Update(
      """
      UPDATE documents
      SET rechunk_strategy = #{strategy},
          rechunk_params_json = #{paramsJson,typeHandler=com.ragforge.mybatis.handler.JsonbStringTypeHandler},
          parse_status = #{status},
          updated_at = NOW()
      WHERE id = #{id}
      """)
  int updateRechunkRequest(
      @Param("id") Long id,
      @Param("strategy") String strategy,
      @Param("paramsJson") String paramsJson,
      @Param("status") String status);

  @Update(
      """
      UPDATE documents
      SET parse_status = 'PROCESSING', updated_at = NOW()
      WHERE id = #{id}
        AND (parse_status = 'PENDING'
             OR parse_status = 'REPROCESSING'
             OR (parse_status = 'PROCESSING' AND updated_at < NOW() - INTERVAL '5 minutes'))
      """)
  int markProcessingIfRunnable(@Param("id") Long id);

  /**
   * 处理中心跳：仅当仍处于 PROCESSING 时刷新 updated_at(M1)。长阶段(如 embedding 超 5min)期间周期性调用，
   * 使 markProcessingIfRunnable 的「PROCESSING 且 updated_at 陈旧 5min」重认领分支不会误判为卡死而并发重跑；
   * 一旦已完成/失败(状态非 PROCESSING)则不匹配、静默无副作用。
   */
  @Update(
      """
      UPDATE documents
      SET updated_at = NOW()
      WHERE id = #{id} AND parse_status = 'PROCESSING'
      """)
  int touchProcessingHeartbeat(@Param("id") Long id);

  @Update(
      """
      UPDATE documents
      SET filename = #{cmd.filename},
          file_path = #{cmd.storageKey},
          file_size = #{cmd.sizeBytes},
          file_type = #{cmd.contentType},
          file_md5 = #{cmd.fileBytesMd5},
          external_id = #{cmd.identity.externalId},
          source_url = #{cmd.identity.sourceUrl},
          content_md5 = #{cmd.identity.contentMd5},
          storage_bucket = #{cmd.storageBucket},
          ingest_source = #{cmd.ingestSource},
          indexed_content = #{cmd.indexedContent},
          chunk_type = #{cmd.chunkType},
          chunk_count = 0,
          version = COALESCE(version, 1) + 1,
          error_msg = NULL,
          updated_at = NOW()
      WHERE id = #{id}
      """)
  int replaceFields(@Param("id") Long id, @Param("cmd") IngestCommand cmd);

  // ---- 压缩包容器：展开领取 / 收尾 / 子文档聚合 ----

  /**
   * CAS 领取容器进入展开：仅当当前状态为 PENDING 时置 EXPANDING（幂等，防 MQ 重投并发重复展开）。 返回受影响行数
   * 0 表示已被其它消费者领取或状态不符，应跳过。
   */
  @Update(
      """
      UPDATE documents
      SET parse_status = 'EXPANDING', updated_at = NOW()
      WHERE id = #{id} AND parse_status = 'PENDING'
      """)
  int claimForExpansion(@Param("id") Long id);

  /** 容器展开收尾：置终态（EXPANDED / FAILED）、写 expand_summary 与 error_msg。 */
  @Update(
      """
      UPDATE documents
      SET parse_status = #{status},
          expand_summary = #{summaryJson,typeHandler=com.ragforge.mybatis.handler.JsonbStringTypeHandler},
          error_msg = #{errorMsg},
          updated_at = NOW()
      WHERE id = #{id}
      """)
  int finishExpansion(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("summaryJson") String summaryJson,
      @Param("errorMsg") String errorMsg);

  /** 容器整包重试：仅当当前为 FAILED / EXPANDED 时重置为 PENDING（供重新展开）。 */
  @Update(
      """
      UPDATE documents
      SET parse_status = 'PENDING', error_msg = NULL, updated_at = NOW()
      WHERE id = #{id}
        AND parent_document_id IS NULL
        AND file_type IN ('zip','tar.gz')
        AND parse_status IN ('FAILED','EXPANDED')
      """)
  int resetContainerForRetry(@Param("id") Long id);

  /** 列出某容器的全部子文档（下钻用），按 id 升序。 */
  @Select("SELECT * FROM documents WHERE parent_document_id = #{parentId} ORDER BY id")
  List<Document> selectChildren(@Param("parentId") Long parentId);

  /** 按子文档 parse_status 分组计数（childrenSummary 用）。返回每行 {status, cnt}。 */
  @Select(
      """
      SELECT parse_status AS status, COUNT(1) AS cnt
      FROM documents
      WHERE parent_document_id = #{parentId}
      GROUP BY parse_status
      """)
  List<Map<String, Object>> countChildrenByStatus(@Param("parentId") Long parentId);
}
