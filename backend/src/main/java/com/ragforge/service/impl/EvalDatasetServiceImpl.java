package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.EvalDatasetVO;
import com.ragforge.service.EvalDatasetService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EvalDatasetServiceImpl implements EvalDatasetService {

  private static final String KB_STATUS_DELETED = "deleted";

  private final EvalDatasetMapper evalDatasetMapper;
  private final EvalQuestionMapper evalQuestionMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final com.ragforge.security.KbAccessGuard kbAccessGuard;

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
    List<EvalDatasetVO> vos =
        evalDatasetMapper.selectList(w).stream().map(EvalDatasetVO::fromEntity).toList();
    // 标记冻结基线：含核心题(is_core)的数据集置 locked=true，前端据此置灰操作按钮。
    Set<Long> lockedIds = coreDatasetIds(vos.stream().map(EvalDatasetVO::getId).toList());
    vos.forEach(v -> v.setLocked(lockedIds.contains(v.getId())));
    return vos;
  }

  /** 返回给定数据集中含核心题(is_core=TRUE)的数据集 id 集合。 */
  private Set<Long> coreDatasetIds(List<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty()) {
      return Set.of();
    }
    return evalQuestionMapper
        .selectList(
            new LambdaQueryWrapper<EvalQuestion>()
                .in(EvalQuestion::getDatasetId, datasetIds)
                .eq(EvalQuestion::getIsCore, true))
        .stream()
        .map(EvalQuestion::getDatasetId)
        .collect(Collectors.toSet());
  }

  /**
   * 当前组织可访问的 KB ids = 本组织的库 + 公开库；仅破玻璃返回 null（不过滤）。
   *
   * <p>无组织上下文（未带 X-Org-Id，如个人组织视图；或非成员 X-Org-Id 被重置为 null）时，
   * 收敛到<b>当前用户可读的 KB 集合</b>（`allReadableKbIds`）而非 null；否则会退化为不过滤，
   * 跨组织泄露全平台评测数据集。与 {@code EvalExperimentServiceImpl} 保持一致。
   */
  private List<Long> currentOrgKbIdsOrNull() {
    com.ragforge.security.RagAuthContext ctx = com.ragforge.security.RagAuthContextHolder.get();
    if (ctx != null && ctx.isAdmin() && com.ragforge.security.AdminOverrideHolder.isActive()) {
      return null;
    }
    Long orgId = com.ragforge.security.OrgContextHolder.get();
    if (orgId == null) {
      return new java.util.ArrayList<>(kbAccessGuard.allReadableKbIds());
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
    // 冻结基线保护：含核心题的数据集不可删除（防误删，与前端置灰双保险）。
    if (!coreDatasetIds(List.of(id)).isEmpty()) {
      throw new BizException(403, "CORE_DATASET_LOCKED");
    }
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
