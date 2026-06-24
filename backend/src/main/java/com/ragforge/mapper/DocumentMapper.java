package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.entity.Document;
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
          error_msg = NULL,
          updated_at = NOW()
      WHERE id = #{id}
      """)
  int replaceFields(@Param("id") Long id, @Param("cmd") IngestCommand cmd);
}
