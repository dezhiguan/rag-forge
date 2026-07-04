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

  /**
   * 组织级启用题数：只统计 dataset 归属 KB 落在给定范围内的启用黄金题。scopeKbIds 为 null 表示平台口径
   * （全量，等价于无参版本）；空集表示无可见 KB → 0。
   */
  int goldenSetEnabledQuestionCount(Set<Long> scopeKbIds);
}
