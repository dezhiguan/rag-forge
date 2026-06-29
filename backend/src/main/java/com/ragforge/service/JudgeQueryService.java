package com.ragforge.service;

import com.ragforge.model.vo.CaseDetailVo;
import com.ragforge.model.vo.CostSummaryVo;
import com.ragforge.model.vo.KbSliceVo;
import com.ragforge.model.vo.OverviewVo;
import com.ragforge.model.vo.WorstCaseVo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public interface JudgeQueryService {

  /** scopeKbIds：当前组织的 KB 范围；null = 破玻璃全平台，空集 = 无数据。 */
  OverviewVo overview(int days, Long kbId, Set<Long> scopeKbIds);

  List<KbSliceVo> byKb(int days, Set<Long> scopeKbIds);

  List<WorstCaseVo> worstCases(int limit, int days, Long kbId, Set<Long> scopeKbIds);

  CaseDetailVo caseDetail(Long judgeResultId);

  CostSummaryVo cost(int days, Set<Long> scopeKbIds);

  BigDecimal costThisMonth();

  int goldenSetEnabledQuestionCount();
}
