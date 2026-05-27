package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.EvalDatasetVO;
import com.ragforge.service.EvalDatasetService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EvalDatasetServiceImpl implements EvalDatasetService {

  private static final String KB_STATUS_DELETED = "deleted";

  private final EvalDatasetMapper evalDatasetMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;

  @Override
  public List<EvalDatasetVO> listAll() {
    return evalDatasetMapper
        .selectList(
            new LambdaQueryWrapper<EvalDataset>().orderByDesc(EvalDataset::getCreatedAt))
        .stream()
        .map(EvalDatasetVO::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public EvalDatasetVO create(CreateEvalDatasetDTO dto) {
    requireActiveKb(dto.getKbId());

    EvalDataset dataset = new EvalDataset();
    dataset.setName(dto.getName().trim());
    dataset.setKbId(dto.getKbId());
    dataset.setQuestionCount(0);
    dataset.setCreatedAt(LocalDateTime.now());
    evalDatasetMapper.insert(dataset);
    return EvalDatasetVO.fromEntity(dataset);
  }

  @Override
  public EvalDatasetVO getById(Long id) {
    return EvalDatasetVO.fromEntity(requireDatasetEntity(id));
  }

  @Override
  @Transactional
  public void delete(Long id) {
    requireDatasetEntity(id);
    evalDatasetMapper.deleteById(id);
  }

  @Override
  public void requireDataset(Long id) {
    requireDatasetEntity(id);
  }

  private EvalDataset requireDatasetEntity(Long id) {
    EvalDataset dataset = evalDatasetMapper.selectById(id);
    if (dataset == null) {
      throw new BizException(404, "评测数据集不存在");
    }
    return dataset;
  }

  private void requireActiveKb(Long kbId) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || KB_STATUS_DELETED.equals(kb.getStatus())) {
      throw new BizException(404, "知识库不存在");
    }
  }
}
