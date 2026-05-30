package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.maintenance.DataCalibrationJob;
import com.ragforge.maintenance.EsIndexRepairJob;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

  private final DataCalibrationJob dataCalibrationJob;
  private final EsIndexRepairJob esIndexRepairJob;

  @PostMapping("/calibrate")
  public Result<Void> calibrate() {
    dataCalibrationJob.calibrateCounters();
    return Result.ok();
  }

  @PostMapping("/repair-es")
  public Result<Void> repairEs() {
    esIndexRepairJob.repairMissingIndexes();
    return Result.ok();
  }
}
