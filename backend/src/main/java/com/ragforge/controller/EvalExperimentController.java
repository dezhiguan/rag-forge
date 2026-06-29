package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.RunEvalExperimentDTO;
import com.ragforge.model.vo.EvalExperimentVO;
import com.ragforge.service.EvalExperimentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/eval/experiments")
@RequiredArgsConstructor
// 下放到组织：登录用户即可访问，数据按所属 KB 的组织逐条隔离(见 EvalExperimentServiceImpl)；
// 可见性由前端 nav 按组织角色(OWNER/ADMIN)控制。
public class EvalExperimentController {

  private final EvalExperimentService evalExperimentService;

  @PostMapping("/run")
  public Result<EvalExperimentVO> run(@Valid @RequestBody RunEvalExperimentDTO dto) {
    return Result.ok(
        evalExperimentService.runExperiment(
            dto.getDatasetId(), dto.getStrategy(), dto.getVectorWeight(), dto.getTopK()));
  }

  @GetMapping
  public Result<List<EvalExperimentVO>> list() {
    return Result.ok(evalExperimentService.listRecent());
  }

  @GetMapping("/{id}")
  public Result<EvalExperimentVO> detail(@PathVariable Long id) {
    return Result.ok(evalExperimentService.getDetail(id));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    evalExperimentService.delete(id);
    return Result.ok();
  }
}

