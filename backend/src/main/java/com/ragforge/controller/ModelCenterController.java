package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.modelcenter.ModelCenterService;
import com.ragforge.modelcenter.vo.CostDetailVo;
import com.ragforge.modelcenter.vo.CostStatsVo;
import com.ragforge.modelcenter.vo.ModelItemVo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模型 & 成本中心。仅 ADMIN 可访问（与 API 网关一致）。 */
@RestController
@RequestMapping("/api/v1/models")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ModelCenterController {

  private final ModelCenterService modelCenterService;

  /** 模型列表（含本月费用）。 */
  @GetMapping
  public Result<List<ModelItemVo>> list() {
    return Result.ok(modelCenterService.listModels());
  }

  /** 成本看板：KPI + 趋势 + 排行。 */
  @GetMapping("/cost/stats")
  public Result<CostStatsVo> costStats(@RequestParam(defaultValue = "7") int days) {
    return Result.ok(modelCenterService.costStats(days));
  }

  /** 调用明细（按用途）。 */
  @GetMapping("/cost/detail")
  public Result<List<CostDetailVo>> costDetail() {
    return Result.ok(modelCenterService.costDetail());
  }
}
