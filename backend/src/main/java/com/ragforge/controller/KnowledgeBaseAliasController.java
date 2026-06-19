package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import com.ragforge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backward/forward compatible alias for docs.
 *
 * <p>Existing routes: /api/v1/kb
 * <p>Alias routes: /api/v1/knowledge-bases
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseAliasController {

  private final KnowledgeBaseService knowledgeBaseService;

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
  public Result<KnowledgeBaseVO> create(@Valid @RequestBody CreateKbDTO dto) {
    KnowledgeBase kb = knowledgeBaseService.create(dto);
    return Result.ok(KnowledgeBaseVO.fromEntity(kb));
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER')")
  public Result<List<KnowledgeBaseVO>> listAll() {
    return Result.ok(knowledgeBaseService.listAll());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER') and @kbAccessGuard.canRead(#id)")
  public Result<KnowledgeBaseVO> getById(@PathVariable Long id) {
    return Result.ok(knowledgeBaseService.getById(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
  public Result<KnowledgeBaseVO> update(
      @PathVariable Long id, @Valid @RequestBody UpdateKbDTO dto) {
    KnowledgeBase kb = knowledgeBaseService.update(id, dto);
    return Result.ok(KnowledgeBaseVO.fromEntity(kb));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
  public Result<Void> delete(@PathVariable Long id) {
    knowledgeBaseService.delete(id);
    return Result.ok();
  }
}

