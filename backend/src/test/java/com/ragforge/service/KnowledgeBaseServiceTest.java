package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class KnowledgeBaseServiceTest {

  @Autowired private KnowledgeBaseService knowledgeBaseService;
  @Autowired private KnowledgeBaseMapper knowledgeBaseMapper;
  @Autowired private DocumentMapper documentMapper;

  @Test
  void deleteKbWithDocumentsShouldFail() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb-with-docs");
    KnowledgeBase kb = knowledgeBaseService.create(dto);

    Document doc = new Document();
    doc.setKbId(kb.getId());
    doc.setFilename("a.pdf");
    doc.setFilePath("/data/a.pdf");
    doc.setParseStatus("pending");
    doc.setChunkCount(0);
    doc.setCreatedAt(LocalDateTime.now());
    documentMapper.insert(doc);

    assertThatThrownBy(() -> knowledgeBaseService.delete(kb.getId()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("1 个文档");
  }

  @Test
  void deleteEmptyKbShouldSoftDelete() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb-empty");
    KnowledgeBase kb = knowledgeBaseService.create(dto);

    knowledgeBaseService.delete(kb.getId());

    KnowledgeBase updated = knowledgeBaseMapper.selectById(kb.getId());
    assertThat(updated.getStatus()).isEqualTo("deleted");
  }
}
