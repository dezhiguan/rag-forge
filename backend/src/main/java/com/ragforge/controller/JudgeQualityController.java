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
  private final com.ragforge.judge.JudgeCostGuardProperties costGuardProperties;
  private final com.ragforge.judge.JudgeBudgetService budgetService;

  /** 组织月度评测预算上限（防误填天价）。 */
  private static final java.math.BigDecimal MAX_MONTHLY_BUDGET = new java.math.BigDecimal("1000000");

  /** 时间窗上限（天）：看板最长按钮为 90 天，留足冗余并防超大范围拖库。 */
  private static final int MAX_DAYS = 365;

  /** worst-cases 返回条数上限：前端固定取 10，设 100 上限防资源放大。 */
  private static final int MAX_LIMIT = 100;

  /** 时间窗范围校验：非法（<1 或超上限）与非数字一样返回 400 INVALID_PARAM:days（一致性）。 */
  private void validateDays(int days) {
    if (days < 1 || days > MAX_DAYS) {
      throw new BizException(400, "INVALID_PARAM:days");
    }
  }

  /** 条数范围校验：<1 或超上限返回 400 INVALID_PARAM:limit。 */
  private void validateLimit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BizException(400, "INVALID_PARAM:limit");
    }
  }

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
    validateDays(days);
    Set<Long> scope = currentOrgScope();
    requireKbInScope(kbId, scope);
    return Result.ok(queryService.overview(days, kbId, scope));
  }

  @GetMapping("/by-kb")
  public Result<List<KbSliceVo>> byKb(
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) Long kbId) {
    validateDays(days);
    // 与 overview/cost/worst-cases 一致：传 kbId 时同样先鉴权（越权/越组织 → 403），再收窄到该 KB。
    Set<Long> scope = currentOrgScope();
    requireKbInScope(kbId, scope);
    return Result.ok(queryService.byKb(days, kbId != null ? Set.of(kbId) : scope));
  }

  @GetMapping("/worst-cases")
  public Result<List<WorstCaseVo>> worstCases(
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) Long kbId) {
    validateDays(days);
    validateLimit(limit);
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
    validateDays(days);
    Set<Long> scope = currentOrgScope();
    requireKbInScope(kbId, scope);
    // 与 overview/worst-cases 一致:按知识库筛选时,成本也只统计该知识库(联动)。
    Set<Long> effectiveScope = kbId != null ? Set.of(kbId) : scope;
    return Result.ok(queryService.cost(days, effectiveScope));
  }

  /**
   * 平台评测预算（全平台共享）：月度预算取部署配置 {@code ragforge.judge.cost-guard.monthly-budget-cny}，
   * 本月已用取全平台本月 judge 成本。用于抽屉「本月评测配额」进度条，均为后端真实值（不前端写死）。
   */
  /**
   * 本月评测配额（按组织）：破玻璃平台视图返回平台默认预算 + 全平台本月已用（只读、共享）；否则返回当前组织的
   * 预算（组织自配或回退默认）+ 本组织本月已用 + 是否超支 + 当前用户是否可编辑。均为后端真实值。
   */
  @GetMapping("/budget")
  public Result<java.util.Map<String, Object>> budget() {
    com.ragforge.security.RagAuthContext ctx = com.ragforge.security.RagAuthContextHolder.get();
    boolean platformView =
        ctx != null && ctx.isAdmin() && com.ragforge.security.AdminOverrideHolder.isActive();
    Long orgId = platformView ? null : com.ragforge.security.OrgContextHolder.get();
    com.ragforge.judge.JudgeBudgetService.BudgetSnapshot snap = budgetService.snapshotForOrg(orgId);
    // 预算按组织分配、由平台管理员配置：仅平台管理员在具体组织上下文下可编辑。
    boolean editable = !platformView && orgId != null && ctx != null && ctx.isAdmin();

    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("monthlyBudgetCny", snap.monthlyBudgetCny());
    body.put("monthUsedCny", snap.monthUsedCny());
    body.put("exceeded", snap.exceeded());
    body.put("editable", editable);
    body.put("platformShared", platformView);
    return Result.ok(body);
  }

  /** 配置当前组织的月度评测预算：仅组织所有者/管理员或平台管理员。 */
  @org.springframework.web.bind.annotation.PutMapping("/budget")
  public Result<java.util.Map<String, Object>> setBudget(
      @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
    com.ragforge.security.RagAuthContext ctx = com.ragforge.security.RagAuthContextHolder.get();
    Long orgId = com.ragforge.security.OrgContextHolder.get();
    if (ctx == null || ctx.userId() == null || orgId == null) {
      throw new BizException(400, "ORG_CONTEXT_REQUIRED");
    }
    // 月度评测预算按组织分配，仅平台管理员可配置（组织超支时联系平台管理员）。
    if (!ctx.isAdmin()) {
      throw new BizException(403, "BUDGET_ADMIN_ONLY");
    }
    java.math.BigDecimal amount;
    try {
      amount = new java.math.BigDecimal(String.valueOf(body.get("monthlyBudgetCny")));
    } catch (RuntimeException e) {
      throw new BizException(400, "INVALID_PARAM:monthlyBudgetCny");
    }
    if (amount.signum() <= 0 || amount.compareTo(MAX_MONTHLY_BUDGET) > 0) {
      throw new BizException(400, "INVALID_PARAM:monthlyBudgetCny");
    }
    budgetService.setBudget(orgId, amount);
    return budget();
  }
}
