package com.ragforge.controller;

import com.ragforge.common.PageResult;
import com.ragforge.common.Result;
import com.ragforge.model.dto.TextUploadRequest;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.service.DocumentService;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
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

  @PostMapping("/kb/{kbId}/documents")
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
  public Result<DocumentUploadResultVO> uploadAlias(
      @RequestParam("kbId") Long kbId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
    return Result.ok(documentService.upload(kbId, file, overwrite));
  }

  @PostMapping("/kb/{kbId}/documents/replace/{docId}")
  public Result<DocumentVO> replace(
      @PathVariable Long kbId,
      @PathVariable Long docId,
      @RequestParam("file") MultipartFile file) {
    return Result.ok(documentService.replaceDocument(kbId, file, docId));
  }

  @GetMapping("/kb/{kbId}/documents")
  public Result<PageResult<DocumentVO>> listByKb(
      @PathVariable Long kbId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size) {
    return Result.ok(documentService.listByKb(kbId, page, size));
  }

  @GetMapping("/documents/{id}")
  public Result<DocumentDetailVO> getById(@PathVariable Long id) {
    return Result.ok(documentService.getById(id));
  }

  @GetMapping("/documents/{id}/chunks")
  public Result<PageResult<DocumentChunkVO>> listChunks(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size) {
    return Result.ok(documentService.listChunks(id, page, size));
  }

  @GetMapping("/documents/{id}/status")
  public Result<DocumentStatusVO> getStatus(@PathVariable Long id) {
    return Result.ok(documentService.getStatus(id));
  }

  @GetMapping("/documents/{id}/download")
  public ResponseEntity<Resource> download(@PathVariable Long id) {
    return documentService.download(id);
  }

  @DeleteMapping("/documents/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    documentService.delete(id);
    return Result.ok();
  }

  @PostMapping("/documents/{id}/reprocess")
  public Result<Void> reprocess(@PathVariable Long id) {
    documentService.reprocess(id);
    return Result.ok();
  }

  @PostMapping("/documents/text")
  public Result<DocumentUploadResultVO> uploadText(
      @RequestBody @jakarta.validation.Valid TextUploadRequest request) {
    return Result.ok(documentService.uploadText(request));
  }
}
