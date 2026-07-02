package com.ragforge.controller;

import com.ragforge.common.PageResult;
import com.ragforge.common.Result;
import com.ragforge.document.dto.UploadRelayResult;
import com.ragforge.document.service.DocumentUploadApplicationService;
import com.ragforge.model.dto.PresignUploadRequest;
import com.ragforge.model.dto.RechunkRequest;
import com.ragforge.model.dto.RegisterUploadRequest;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.service.DocumentService;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentService documentService;
  private final DocumentUploadApplicationService uploadApplicationService;

  // 写权限校验下沉到 service：presignUpload 内部对 canWrite 失败抛 403 KB_WRITE_FORBIDDEN，
  // 这样错误码语义明确，而不是被 @PreAuthorize 拦成通用 "Forbidden"。
  @PostMapping("/uploads/presign")
  public ResponseEntity<?> presignUpload(@RequestBody PresignUploadRequest request) {
    return ResponseEntity.ok(uploadApplicationService.presignUpload(request));
  }

  @PostMapping("/documents/register")
  @PreAuthorize("@kbAccessGuard.canWrite(#request.kbId)")
  public ResponseEntity<?> registerUploadedDocument(@RequestBody RegisterUploadRequest request) {
    return ResponseEntity.ok(uploadApplicationService.registerUploadedDocument(request));
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> uploadV5(
      @RequestParam("file") MultipartFile file, @RequestParam("meta") String metaJson) {
    UploadRelayResult result = uploadApplicationService.relayUpload(file, metaJson);
    return ResponseEntity.status(result.getStatus()).body(result.getBody());
  }

  @PostMapping("/kb/{kbId}/documents")
  @PreAuthorize("@kbAccessGuard.canWrite(#kbId)")
  public Result<DocumentUploadResultVO> upload(
      @PathVariable Long kbId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
    return Result.ok(documentService.upload(kbId, file, overwrite));
  }

  /**
   * Backward/forward compatible alias for docs.
   *
   * <p>Alias route: /api/v1/documents/upload?kbId=1&overwrite=true
   */
  @PostMapping("/documents/upload")
  @PreAuthorize("@kbAccessGuard.canWrite(#kbId)")
  public Result<DocumentUploadResultVO> uploadAlias(
      @RequestParam("kbId") Long kbId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
    return Result.ok(documentService.upload(kbId, file, overwrite));
  }

  @PostMapping("/kb/{kbId}/documents/replace/{docId}")
  @PreAuthorize("@kbAccessGuard.canWrite(#kbId)")
  public Result<DocumentVO> replace(
      @PathVariable Long kbId,
      @PathVariable Long docId,
      @RequestParam("file") MultipartFile file) {
    return Result.ok(documentService.replaceDocument(kbId, file, docId));
  }

  @GetMapping("/kb/{kbId}/documents")
  @PreAuthorize("@kbAccessGuard.canRead(#kbId)")
  public Result<PageResult<DocumentVO>> listByKb(
      @PathVariable Long kbId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean flatten) {
    // 默认(知识库列表层)走既有 4 参重载；flatten(文档列表层)走平铺重载
    return Result.ok(
        flatten
            ? documentService.listByKb(kbId, page, size, keyword, true)
            : documentService.listByKb(kbId, page, size, keyword));
  }

  @GetMapping("/documents/{id}")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<DocumentDetailVO> getById(@PathVariable Long id) {
    return Result.ok(documentService.getById(id));
  }

  @GetMapping("/documents/{id}/children")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<java.util.List<DocumentVO>> listChildren(@PathVariable Long id) {
    return Result.ok(documentService.listChildren(id));
  }

  @GetMapping("/documents/{id}/chunks")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<PageResult<DocumentChunkVO>> listChunks(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size) {
    return Result.ok(documentService.listChunks(id, page, size));
  }

  @GetMapping("/documents/{id}/status")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<DocumentStatusVO> getStatus(@PathVariable Long id) {
    return Result.ok(documentService.getStatus(id));
  }

  @GetMapping("/documents/{id}/download")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public ResponseEntity<Resource> download(@PathVariable Long id) {
    return documentService.download(id);
  }

  @DeleteMapping("/documents/{id}")
  @PreAuthorize("@kbAccessGuard.canWriteDocument(#id)")
  public Result<Void> delete(@PathVariable Long id) {
    documentService.delete(id);
    return Result.ok();
  }

  @PostMapping("/documents/{id}/reprocess")
  @PreAuthorize("@kbAccessGuard.canWriteDocument(#id)")
  public ResponseEntity<Map<String, Object>> reprocess(@PathVariable Long id) {
    return ResponseEntity.ok(uploadApplicationService.reprocess(id));
  }

  @PostMapping("/documents/{id}/rechunk")
  @PreAuthorize("@kbAccessGuard.canWriteDocument(#id)")
  public ResponseEntity<Map<String, Object>> rechunk(
      @PathVariable Long id, @RequestBody(required = false) RechunkRequest req) {
    return ResponseEntity.ok(uploadApplicationService.rechunk(id, req));
  }
}
