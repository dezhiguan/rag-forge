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
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

  @Test
  void listAll_loadsActiveKnowledgeBases() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(10L);
    kb.setName("cached-kb");
    kb.setStatus("active");
    kb.setCreatedAt(LocalDateTime.now());
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(kb));

    List<KnowledgeBaseVO> first = knowledgeBaseService.listAll();
    List<KnowledgeBaseVO> second = knowledgeBaseService.listAll();

    assertThat(first).hasSize(1);
    assertThat(first.get(0).getName()).isEqualTo("cached-kb");
    assertThat(second).isSameAs(first);
  }

  @Test
  void getById_returnsVoForActiveKb() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(20L);
    kb.setName("found");
    kb.setStatus("active");
    when(knowledgeBaseMapper.selectById(20L)).thenReturn(kb);

    KnowledgeBaseVO vo = knowledgeBaseService.getById(20L);

    assertThat(vo.getId()).isEqualTo(20L);
    assertThat(vo.getName()).isEqualTo("found");
  }

  @Test
  void getById_missingOrDeletedThrows404() {
    when(knowledgeBaseMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> knowledgeBaseService.getById(404L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(404);
  }

  @Test
  void updateAppliesProvidedFields() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(30L);
    kb.setName("old");
    kb.setStatus("active");
    when(knowledgeBaseMapper.selectById(30L)).thenReturn(kb);

    UpdateKbDTO dto = new UpdateKbDTO();
    dto.setName("  new-name  ");
    dto.setDescription("desc");
    dto.setChunkSize(256);
    dto.setChunkOverlap(32);

    KnowledgeBase updated = knowledgeBaseService.update(30L, dto);

    assertThat(updated.getName()).isEqualTo("new-name");
    assertThat(updated.getDescription()).isEqualTo("desc");
    assertThat(updated.getChunkSize()).isEqualTo(256);
    assertThat(updated.getChunkOverlap()).isEqualTo(32);
    verify(knowledgeBaseMapper).updateById(kb);
  }
}
