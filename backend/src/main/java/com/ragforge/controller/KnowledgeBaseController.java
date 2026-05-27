package com.ragforge.controller;

import com.ragforge.common.PageResult;
import com.ragforge.common.Result;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.KnowledgeBase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

  private final KnowledgeBaseMapper knowledgeBaseMapper;

  @GetMapping
  public Result<PageResult<KnowledgeBase>> list() {
    List<KnowledgeBase> list = knowledgeBaseMapper.selectList(null);
    return Result.ok(PageResult.of(list.size(), 1, list.size(), list));
  }
}
