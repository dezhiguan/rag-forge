package com.ragforge.controller;

import com.ragforge.common.PageResult;
import com.ragforge.common.Result;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.service.DocumentService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentService documentService;

  @PostMapping("/kb/{kbId}/documents")
  public Result<DocumentVO> upload(
      @PathVariable Long kbId, @RequestParam("file") MultipartFile file) {
    return Result.ok(documentService.upload(kbId, file));
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

  @GetMapping("/documents/{id}/status")
  public Result<DocumentStatusVO> getStatus(@PathVariable Long id) {
    return Result.ok(documentService.getStatus(id));
  }

  @DeleteMapping("/documents/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    documentService.delete(id);
    return Result.ok();
  }
}

