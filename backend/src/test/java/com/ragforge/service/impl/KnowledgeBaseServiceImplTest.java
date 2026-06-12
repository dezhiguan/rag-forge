package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;

  @InjectMocks private KnowledgeBaseServiceImpl knowledgeBaseService;

  @BeforeEach
  void stubInsertAssignsId() {
    when(knowledgeBaseMapper.insert(any(KnowledgeBase.class)))
        .thenAnswer(
            invocation -> {
              KnowledgeBase kb = invocation.getArgument(0);
              kb.setId(1L);
              return 1;
            });
  }

  @Test
  void deleteKbWithDocumentsShouldFail() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb-with-docs");
    KnowledgeBase kb = knowledgeBaseService.create(dto);

    when(knowledgeBaseMapper.selectById(kb.getId())).thenReturn(kb);
    when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

    assertThatThrownBy(() -> knowledgeBaseService.delete(kb.getId()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("1 个文档");
  }

  @Test
  void deleteEmptyKbShouldSoftDelete() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb-empty");
    KnowledgeBase kb = knowledgeBaseService.create(dto);

    when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    when(knowledgeBaseMapper.selectById(kb.getId())).thenReturn(kb);

    knowledgeBaseService.delete(kb.getId());

    ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
    verify(knowledgeBaseMapper).updateById(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("deleted");
  }

  @Test
  void createPersistsActiveKnowledgeBase() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("  new-kb  ");

    KnowledgeBase created = knowledgeBaseService.create(dto);

    assertThat(created.getId()).isEqualTo(1L);
    assertThat(created.getName()).isEqualTo("new-kb");
    assertThat(created.getStatus()).isEqualTo("active");
    verify(knowledgeBaseMapper).insert(eq(created));
  }
}
