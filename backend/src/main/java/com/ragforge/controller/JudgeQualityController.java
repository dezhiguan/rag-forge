package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.common.BizException;
import com.ragforge.model.vo.CaseDetailVo;
import com.ragforge.model.vo.CostSummaryVo;
import com.ragforge.model.vo.KbSliceVo;
import com.ragforge.model.vo.OverviewVo;
import com.ragforge.model.vo.WorstCaseVo;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.service.JudgeQueryService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation/quality")
// 下放到组织：登录用户即可访问，所有读接口按当前组织(含公开库)过滤、按 KB canRead 兜底。
@RequiredArgsConstructor
public class JudgeQualityController {

  private final JudgeQueryService queryService;
  private final KbAccessGuard kbAccessGuard;
  private final com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

  /** 当前组织的 KB 范围；破玻璃返回 null(全平台)，无组织上下文返回空集(无数据)。 */
  private Set<Long> currentOrgScope() {
    com.ragforge.security.RagAuthContext ctx = com.ragforge.security.RagAuthContextHolder.get();
    if (ctx != null && ctx.isAdmin() && com.ragforge.security.AdminOverrideHolder.isActive()) {
      return null;
    }
    Long orgId = com.ragforge.security.OrgContextHolder.get();
    if (orgId == null) {
      return Set.of();
    }
    return new HashSet<>(
        knowledgeBaseMapper
            .selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.ragforge.model.entity.KnowledgeBase>()
                    .ne(com.ragforge.model.entity.KnowledgeBase::getStatus, "deleted")
                    .and(
                        w ->
                            w.eq(com.ragforge.model.entity.KnowledgeBase::getOrgId, orgId)
                                .or()
                                .eq(com.ragforge.model.entity.KnowledgeBase::getVisibility, "PUBLIC")))
            .stream()
            .map(com.ragforge.model.entity.KnowledgeBase::getId)
            .toList());
  }

  @GetMapping("/overview")
  public Result<OverviewVo> overview(
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) Long kbId) {
    Set<Long> scope = currentOrgScope();
    requireKbInScope(kbId, scope);
    return Result.ok(queryService.overview(days, kbId, scope));
  }

  @GetMapping("/by-kb")
  public Result<List<KbSliceVo>> byKb(@RequestParam(defaultValue = "7") int days) {
    return Result.ok(queryService.byKb(days, currentOrgScope()));
  }

  @GetMapping("/worst-cases")
  public Result<List<WorstCaseVo>> worstCases(
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) Long kbId) {
    Set<Long> scope = currentOrgScope();
    requireKbInScope(kbId, scope);
    return Result.ok(queryService.worstCases(limit, days, kbId, scope));
  }

  /**
   * kbId 筛选必须与当前组织口径一致（OS-B1）：kbId 非空时会绕过 scope 直查该 KB，若只校验 canRead，
   * 跨组织成员切换组织后残留的旧组织 kbId 仍可查到他组织统计（口径错位）。
   *
   * <p>语义分层：①始终要求 canRead；②团队组织（scope 非空）额外要求 kbId 落在本组织范围内，堵住
   * 团队→团队切换的残留泄漏；③个人组织/无组织上下文（scope 空）与破玻璃（scope=null）不做范围收窄，
   * 避免误伤「个人组织无 X-Org-Id、按自己个人库筛选」的合法场景。
   */
  private void requireKbInScope(Long kbId, Set<Long> scope) {
    if (kbId == null) {
      return;
    }
    boolean outOfOrgScope = scope != null && !scope.isEmpty() && !scope.contains(kbId);
    if (!kbAccessGuard.canRead(kbId) || outOfOrgScope) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
  }

  @GetMapping("/case/{judgeResultId}")
  public Result<CaseDetailVo> caseDetail(@PathVariable Long judgeResultId) {
    CaseDetailVo detail = queryService.caseDetail(judgeResultId);
    if (detail == null) {
      throw new BizException(404, "JUDGE_RESULT_NOT_FOUND");
    }
    if (detail.getKbIds() != null
        && !detail.getKbIds().isEmpty()
        && detail.getKbIds().stream().anyMatch(kbId -> !kbAccessGuard.canRead(kbId))) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
    return Result.ok(detail);
  }

  @GetMapping("/cost")
  public Result<CostSummaryVo> cost(
      @RequestParam(defaultValue = "30") int days,
      @RequestParam(required = false) Long kbId) {
    Set<Long> scope = currentOrgScope();
    requireKbInScope(kbId, scope);
    // 与 overview/worst-cases 一致:按知识库筛选时,成本也只统计该知识库(联动)。
    Set<Long> effectiveScope = kbId != null ? Set.of(kbId) : scope;
    return Result.ok(queryService.cost(days, effectiveScope));
  }
}
