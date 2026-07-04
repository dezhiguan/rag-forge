package com.ragforge.judge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgJudgeBudgetMapper;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.OrgJudgeBudget;
import com.ragforge.service.JudgeQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织级 LLM-as-Judge 月度评测预算：解析预算（组织自配或回退平台默认）、本月已用、是否超支、配置。 用于抽屉「本月评测配额」按组织展示与
 * 组织级回放的真实拦截。
 */
@Service
@RequiredArgsConstructor
public class JudgeBudgetService {

  private final OrgJudgeBudgetMapper budgetMapper;
  private final JudgeQueryService judgeQueryService;
  private final JudgeCostGuardProperties costGuardProperties;
  private final KnowledgeBaseMapper knowledgeBaseMapper;

  /** 预算快照。 */
  public record BudgetSnapshot(BigDecimal monthlyBudgetCny, BigDecimal monthUsedCny, boolean exceeded) {}

  /** 当前组织自有 KB id 集（不含公开库），与组织级黄金集回放口径一致。orgId 为 null 返回 null（平台口径）。 */
  public Set<Long> orgKbIds(Long orgId) {
    if (orgId == null) {
      return null;
    }
    return knowledgeBaseMapper
        .selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .ne(KnowledgeBase::getStatus, "deleted")
                .eq(KnowledgeBase::getOrgId, orgId))
        .stream()
        .map(KnowledgeBase::getId)
        .collect(Collectors.toSet());
  }

  /** 按组织快照：内部按组织自有 KB 统计本月已用。 */
  public BudgetSnapshot snapshotForOrg(Long orgId) {
    return snapshot(orgId, orgKbIds(orgId));
  }

  /** 组织预算：组织自配优先，未配置回退平台默认（部署配置）。 */
  public BigDecimal resolveBudget(Long orgId) {
    if (orgId != null) {
      OrgJudgeBudget b = budgetMapper.selectById(orgId);
      if (b != null && b.getMonthlyBudgetCny() != null) {
        return b.getMonthlyBudgetCny();
      }
    }
    return costGuardProperties.getMonthlyBudgetCny();
  }

  public BigDecimal monthUsed(Set<Long> scopeKbIds) {
    BigDecimal used = judgeQueryService.judgeCostThisMonth(scopeKbIds);
    return used == null ? BigDecimal.ZERO : used;
  }

  public BudgetSnapshot snapshot(Long orgId, Set<Long> scopeKbIds) {
    BigDecimal budget = resolveBudget(orgId);
    BigDecimal used = monthUsed(scopeKbIds);
    boolean exceeded = budget.signum() > 0 && used.compareTo(budget) >= 0;
    return new BudgetSnapshot(budget, used, exceeded);
  }

  public boolean isExceeded(Long orgId, Set<Long> scopeKbIds) {
    return snapshot(orgId, scopeKbIds).exceeded();
  }

  @Transactional
  public void setBudget(Long orgId, BigDecimal amount) {
    OrgJudgeBudget b = new OrgJudgeBudget();
    b.setOrgId(orgId);
    b.setMonthlyBudgetCny(amount);
    b.setUpdatedAt(LocalDateTime.now());
    if (budgetMapper.selectById(orgId) != null) {
      budgetMapper.updateById(b);
    } else {
      budgetMapper.insert(b);
    }
  }
}
