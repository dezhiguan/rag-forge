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
    LambdaQueryWrapper<EvalDataset> w =
        new LambdaQueryWrapper<EvalDataset>().orderByDesc(EvalDataset::getCreatedAt);
    // 组织过滤：仅当前组织 KB 的数据集；破玻璃/无组织上下文不过滤。
    List<Long> scopeKbIds = currentOrgKbIdsOrNull();
    if (scopeKbIds != null) {
      if (scopeKbIds.isEmpty()) {
        return List.of();
      }
      w.in(EvalDataset::getKbId, scopeKbIds);
    }
    return evalDatasetMapper.selectList(w).stream().map(EvalDatasetVO::fromEntity).toList();
  }

  /** 当前组织可访问的 KB ids = 本组织的库 + 公开库；破玻璃/无组织上下文返回 null（不过滤）。 */
  private List<Long> currentOrgKbIdsOrNull() {
    com.ragforge.security.RagAuthContext ctx = com.ragforge.security.RagAuthContextHolder.get();
    if (ctx != null && ctx.isAdmin() && com.ragforge.security.AdminOverrideHolder.isActive()) {
      return null;
    }
    Long orgId = com.ragforge.security.OrgContextHolder.get();
    if (orgId == null) {
      return null;
    }
    return knowledgeBaseMapper
        .selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .ne(KnowledgeBase::getStatus, KB_STATUS_DELETED)
                .and(
                    w ->
                        w.eq(KnowledgeBase::getOrgId, orgId)
                            .or()
                            .eq(KnowledgeBase::getVisibility, "PUBLIC")))
        .stream()
        .map(KnowledgeBase::getId)
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
    requireKbInCurrentOrg(dataset.getKbId()); // 逐条组织隔离：数据集所属 KB 必须在当前组织
    return dataset;
  }

  private void requireActiveKb(Long kbId) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || KB_STATUS_DELETED.equals(kb.getStatus())) {
      throw new BizException(404, "知识库不存在");
    }
    requireKbInCurrentOrg(kbId); // 创建评测集只能用本组织的 KB
  }

  /** 资源所属 KB 必须在当前组织；破玻璃(全平台)不限制。 */
  private void requireKbInCurrentOrg(Long kbId) {
    List<Long> scope = currentOrgKbIdsOrNull();
    if (scope == null) {
      return;
    }
    if (kbId == null || !scope.contains(kbId)) {
      throw new BizException(403, "EVAL_RESOURCE_NOT_IN_ORG");
    }
  }
}
