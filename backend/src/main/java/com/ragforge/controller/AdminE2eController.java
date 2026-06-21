package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.vo.AdminChunkRawVO;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/e2e")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminE2eController {

  private final JdbcTemplate jdbcTemplate;

  @GetMapping("/chunks/{docId}/raw")
  public Result<List<AdminChunkRawVO>> listRawChunks(@PathVariable Long docId) {
    return Result.ok(
        jdbcTemplate.query(
            """
            SELECT id,
                   array_length(vl_vector::real[], 1) AS vl_vector_dim,
                   chunk_modality,
                   chunk_metadata_json::text AS chunk_metadata_json
            FROM document_chunks
            WHERE doc_id = ?
            ORDER BY chunk_index, id
            """,
            (rs, rowNum) ->
                new AdminChunkRawVO(
                    rs.getLong("id"),
                    (Integer) rs.getObject("vl_vector_dim"),
                    rs.getString("chunk_modality"),
                    rs.getString("chunk_metadata_json")),
            docId));
  }

  @PostMapping("/documents/{docId}/status")
  public Result<Void> updateDocumentStatus(
      @PathVariable Long docId, @RequestBody StatusUpdateRequest request) {
    jdbcTemplate.update(
        "UPDATE documents SET parse_status = ?, updated_at = NOW() WHERE id = ?",
        request.getStatus(),
        docId);
    return Result.ok();
  }

  @PostMapping("/kb/{kbId}/image-mode")
  public Result<Void> updateKbImageMode(
      @PathVariable Long kbId, @RequestBody ImageModeUpdateRequest request) {
    jdbcTemplate.update(
        "UPDATE knowledge_bases SET image_processing_mode = ?, updated_at = NOW() WHERE id = ?",
        request.getMode(),
        kbId);
    return Result.ok();
  }

  @Data
  public static class StatusUpdateRequest {
    private String status;
  }

  @Data
  public static class ImageModeUpdateRequest {
    private String mode;
  }
}
