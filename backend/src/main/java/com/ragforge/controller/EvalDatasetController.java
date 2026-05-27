package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.vo.EvalDatasetVO;
import com.ragforge.service.EvalDatasetService;
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
@RequestMapping("/api/v1/eval/datasets")
@RequiredArgsConstructor
public class EvalDatasetController {

  private final EvalDatasetService evalDatasetService;

  @GetMapping
  public Result<List<EvalDatasetVO>> listAll() {
    return Result.ok(evalDatasetService.listAll());
  }

  @PostMapping
  public Result<EvalDatasetVO> create(@Valid @RequestBody CreateEvalDatasetDTO dto) {
    return Result.ok(evalDatasetService.create(dto));
  }

  @GetMapping("/{id}")
  public Result<EvalDatasetVO> getById(@PathVariable Long id) {
    return Result.ok(evalDatasetService.getById(id));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    evalDatasetService.delete(id);
    return Result.ok();
  }
}
