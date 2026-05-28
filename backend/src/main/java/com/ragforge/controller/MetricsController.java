package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.vo.DashboardMetricsVO;
import com.ragforge.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

  private final MetricsService metricsService;

  @GetMapping("/dashboard")
  public Result<DashboardMetricsVO> dashboard() {
    return Result.ok(metricsService.dashboard());
  }
}

