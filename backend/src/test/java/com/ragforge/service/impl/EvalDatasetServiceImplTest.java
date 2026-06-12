package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.KnowledgeBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvalDatasetServiceImplTest {

  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  @InjectMocks private EvalDatasetServiceImpl evalDatasetService;

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
  void delete_removesDataset() {
    when(evalDatasetMapper.selectById(3L)).thenReturn(dataset(3L, "old", 10L));

    evalDatasetService.delete(3L);

    verify(evalDatasetMapper).deleteById(3L);
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
