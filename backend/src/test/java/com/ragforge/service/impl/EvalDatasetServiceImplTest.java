package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.security.RagAuthContextHolder;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvalDatasetServiceImplTest {

  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private EvalQuestionMapper evalQuestionMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  @InjectMocks private EvalDatasetServiceImpl evalDatasetService;

  @AfterEach
  void tearDown() {
    OrgContextHolder.clear();
    RagAuthContextHolder.clear();
  }

  @Test
  void listAll_mapsEntities() {
    EvalDataset dataset = dataset(1L, "ds-1", 10L);
    when(evalDatasetMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(dataset));

    var list = evalDatasetService.listAll();

    assertThat(list).hasSize(1);
    assertThat(list.get(0).getName()).isEqualTo("ds-1");
  }

  @Test
  void create_persistsDataset() {
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(activeKb(10L));

    CreateEvalDatasetDTO dto = new CreateEvalDatasetDTO();
    dto.setName("  eval-set  ");
    dto.setKbId(10L);

    var vo = evalDatasetService.create(dto);

    assertThat(vo.getName()).isEqualTo("eval-set");
    assertThat(vo.getKbId()).isEqualTo(10L);
    verify(evalDatasetMapper).insert(any(EvalDataset.class));
  }

  @Test
  void create_missingKb_throws404() {
    when(knowledgeBaseMapper.selectById(404L)).thenReturn(null);

    CreateEvalDatasetDTO dto = new CreateEvalDatasetDTO();
    dto.setName("ds");
    dto.setKbId(404L);

    assertThatThrownBy(() -> evalDatasetService.create(dto))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void create_deletedKb_throws404() {
    KnowledgeBase deleted = activeKb(10L);
    deleted.setStatus("deleted");
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(deleted);

    CreateEvalDatasetDTO dto = new CreateEvalDatasetDTO();
    dto.setName("ds");
    dto.setKbId(10L);

    assertThatThrownBy(() -> evalDatasetService.create(dto))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
    verify(evalDatasetMapper, never()).insert(any(EvalDataset.class));
  }

  @Test
  void listAll_withOrgScopeReturnsEmptyWithoutQueryingDatasetsWhenNoKbInScope() {
    OrgContextHolder.set(7L);
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    var list = evalDatasetService.listAll();

    assertThat(list).isEmpty();
    verify(evalDatasetMapper, never()).selectList(any(LambdaQueryWrapper.class));
  }

  @Test
  void getById_datasetOutsideCurrentOrg_throws403() {
    OrgContextHolder.set(7L);
    when(evalDatasetMapper.selectById(9L)).thenReturn(dataset(9L, "foreign", 99L));
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeKb(10L)));

    assertThatThrownBy(() -> evalDatasetService.getById(9L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403))
        .hasMessageContaining("EVAL_RESOURCE_NOT_IN_ORG");
  }

  @Test
  void requireDataset_datasetInCurrentOrg_passes() {
    OrgContextHolder.set(7L);
    when(evalDatasetMapper.selectById(9L)).thenReturn(dataset(9L, "local", 10L));
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeKb(10L)));

    evalDatasetService.requireDataset(9L);

    verify(evalDatasetMapper).selectById(9L);
  }

  @Test
  void delete_removesDataset() {
    when(evalDatasetMapper.selectById(3L)).thenReturn(dataset(3L, "old", 10L));

    evalDatasetService.delete(3L);

    verify(evalDatasetMapper).deleteById(3L);
  }

  @Test
  void delete_coreDataset_throws403AndKeepsDataset() {
    when(evalDatasetMapper.selectById(3L)).thenReturn(dataset(3L, "baseline", 10L));
    EvalQuestion core = new EvalQuestion();
    core.setDatasetId(3L);
    core.setIsCore(true);
    when(evalQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(core));

    assertThatThrownBy(() -> evalDatasetService.delete(3L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("CORE_DATASET_LOCKED");
    verify(evalDatasetMapper, never()).deleteById(3L);
  }

  @Test
  void getById_missingDataset_throws404() {
    when(evalDatasetMapper.selectById(8L)).thenReturn(null);

    assertThatThrownBy(() -> evalDatasetService.getById(8L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("评测数据集不存在");
  }

  private static EvalDataset dataset(long id, String name, long kbId) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(id);
    dataset.setName(name);
    dataset.setKbId(kbId);
    dataset.setQuestionCount(0);
    dataset.setCreatedAt(LocalDateTime.now());
    return dataset;
  }

  private static KnowledgeBase activeKb(long id) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setStatus("active");
    return kb;
  }
}
