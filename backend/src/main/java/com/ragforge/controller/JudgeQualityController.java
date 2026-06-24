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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation/quality")
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
@RequiredArgsConstructor
public class JudgeQualityController {

  private final JudgeQueryService queryService;
  private final KbAccessGuard kbAccessGuard;

  @GetMapping("/overview")
  public Result<OverviewVo> overview(
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) Long kbId) {
    if (kbId != null && !kbAccessGuard.canRead(kbId)) {
      throw new com.ragforge.common.BizException(403, "KB_ACCESS_DENIED");
    }
    return Result.ok(queryService.overview(days, kbId));
  }

  @GetMapping("/by-kb")
  public Result<List<KbSliceVo>> byKb(@RequestParam(defaultValue = "7") int days) {
    Set<Long> readableKbIds = new HashSet<>(kbAccessGuard.allReadableKbIds());
    return Result.ok(queryService.byKb(days, readableKbIds));
  }

  @GetMapping("/worst-cases")
  public Result<List<WorstCaseVo>> worstCases(
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) Long kbId) {
    Set<Long> readableKbIds = Set.of();
    if (kbId != null && !kbAccessGuard.canRead(kbId)) {
      throw new com.ragforge.common.BizException(403, "KB_ACCESS_DENIED");
    }
    if (kbId == null) {
      readableKbIds = new HashSet<>(kbAccessGuard.allReadableKbIds());
    }
    return Result.ok(queryService.worstCases(limit, days, kbId, readableKbIds));
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
  public Result<CostSummaryVo> cost(@RequestParam(defaultValue = "30") int days) {
    return Result.ok(queryService.cost(days));
  }
}
