package com.ragforge.service;

import com.ragforge.model.vo.CaseDetailVo;
import com.ragforge.model.vo.CostSummaryVo;
import com.ragforge.model.vo.KbSliceVo;
import com.ragforge.model.vo.OverviewVo;
import com.ragforge.model.vo.WorstCaseVo;
import java.util.List;
import java.util.Set;

public interface JudgeQueryService {

  OverviewVo overview(int days, Long kbId);

  List<KbSliceVo> byKb(int days, Set<Long> readableKbIds);

  List<WorstCaseVo> worstCases(int limit, int days, Long kbId);

  CaseDetailVo caseDetail(Long judgeResultId);

  CostSummaryVo cost(int days);
}
