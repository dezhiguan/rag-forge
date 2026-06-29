package com.ragforge.controller;

import com.ragforge.common.PageResult;
import com.ragforge.common.Result;
import com.ragforge.model.dto.CreateEvalQuestionDTO;
import com.ragforge.model.dto.SaveQuestionFromSearchDTO;
import com.ragforge.model.vo.EvalQuestionVO;
import com.ragforge.service.EvalQuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/eval/datasets/{datasetId}/questions")
@RequiredArgsConstructor
// 下放到组织：登录用户即可访问，数据集经 requireDataset 按组织逐条隔离；
// 可见性由前端 nav 按组织角色(OWNER/ADMIN)控制。
public class EvalQuestionController {

  private final EvalQuestionService evalQuestionService;

  @GetMapping
  public Result<PageResult<EvalQuestionVO>> listByDataset(
      @PathVariable Long datasetId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size) {
    return Result.ok(evalQuestionService.listByDataset(datasetId, page, size));
  }

  @PostMapping
  public Result<EvalQuestionVO> create(
      @PathVariable Long datasetId, @Valid @RequestBody CreateEvalQuestionDTO dto) {
    return Result.ok(evalQuestionService.create(datasetId, dto));
  }

  @PostMapping("/batch")
  public Result<List<EvalQuestionVO>> batchCreate(
      @PathVariable Long datasetId, @Valid @RequestBody List<CreateEvalQuestionDTO> questions) {
    return Result.ok(evalQuestionService.batchCreate(datasetId, questions));
  }

  @PostMapping("/from-search")
  public Result<EvalQuestionVO> createFromSearch(
      @PathVariable Long datasetId, @Valid @RequestBody SaveQuestionFromSearchDTO dto) {
    return Result.ok(evalQuestionService.createFromSearch(datasetId, dto));
  }

  @PutMapping("/{id}")
  public Result<EvalQuestionVO> update(
      @PathVariable Long datasetId,
      @PathVariable Long id,
      @Valid @RequestBody CreateEvalQuestionDTO dto) {
    return Result.ok(evalQuestionService.update(datasetId, id, dto));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long datasetId, @PathVariable Long id) {
    evalQuestionService.delete(datasetId, id);
    return Result.ok();
  }
}
