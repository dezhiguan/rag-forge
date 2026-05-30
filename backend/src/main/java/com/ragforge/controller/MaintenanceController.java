package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.maintenance.DataCalibrationJob;
import com.ragforge.maintenance.DataCalibrationReport;
import com.ragforge.maintenance.EsRepairReport;
import com.ragforge.maintenance.EsIndexRepairJob;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
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
  public Result<DataCalibrationReport> calibrate() {
    return Result.ok(dataCalibrationJob.calibrate());
  }

  @PostMapping("/repair-es")
  public Result<EsRepairReport> repairEs() {
    return Result.ok(esIndexRepairJob.repairAllMissingIndexes());
  }

  @PostMapping("/repair-es/{docId}")
  public Result<EsRepairReport> repairEsDocument(@PathVariable Long docId) {
    return Result.ok(esIndexRepairJob.repairDocument(docId));
  }
}
