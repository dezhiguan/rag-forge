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

@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

  private final KnowledgeBaseService knowledgeBaseService;

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','USER')")
  public Result<KnowledgeBaseVO> create(@Valid @RequestBody CreateKbDTO dto) {
    KnowledgeBase kb = knowledgeBaseService.create(dto);
    // 用 getById 回填 myPermission（创建者=admin）与组织库的 orgName，
    // 保持创建响应与详情/列表的字段口径一致。
    return Result.ok(knowledgeBaseService.getById(kb.getId()));
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','USER')")
  public Result<List<KnowledgeBaseVO>> listAll() {
    return Result.ok(knowledgeBaseService.listVisibleToCurrentUser());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','USER') and @kbAccessGuard.canRead(#id)")
  public Result<KnowledgeBaseVO> getById(@PathVariable Long id) {
    return Result.ok(knowledgeBaseService.getById(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','USER') and @kbAccessGuard.canWrite(#id)")
  public Result<KnowledgeBaseVO> update(
      @PathVariable Long id, @Valid @RequestBody UpdateKbDTO dto) {
    KnowledgeBase kb = knowledgeBaseService.update(id, dto);
    return Result.ok(KnowledgeBaseVO.fromEntity(kb));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','USER') and @kbAccessGuard.canAdmin(#id)")
  public Result<Void> delete(@PathVariable Long id) {
    knowledgeBaseService.delete(id);
    return Result.ok();
  }
}
