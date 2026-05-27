package com.ragforge.mapper;

import static org.assertj.core.api.Assertions.assertThat;

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
class KnowledgeBaseMapperTest {

  @Autowired private KnowledgeBaseMapper knowledgeBaseMapper;

  @Test
  void baseMapperInsertSelectDelete() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setName("test-kb");
    kb.setDescription("mapper smoke test");
    kb.setEmbeddingModel("text-embedding-v4");
    kb.setChunkSize(512);
    kb.setChunkOverlap(64);
    kb.setDocCount(0);
    kb.setChunkCount(0);
    kb.setStatus("active");
    kb.setCreatedAt(LocalDateTime.now());
    kb.setUpdatedAt(LocalDateTime.now());

    assertThat(knowledgeBaseMapper.insert(kb)).isEqualTo(1);
    assertThat(kb.getId()).isNotNull();

    KnowledgeBase loaded = knowledgeBaseMapper.selectById(kb.getId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getName()).isEqualTo("test-kb");

    assertThat(knowledgeBaseMapper.deleteById(kb.getId())).isEqualTo(1);
  }
}
