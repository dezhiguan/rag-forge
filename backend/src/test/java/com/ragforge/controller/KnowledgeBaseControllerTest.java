package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import com.ragforge.service.KnowledgeBaseService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTest {

  @Mock private KnowledgeBaseService knowledgeBaseService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        standaloneSetup(new KnowledgeBaseController(knowledgeBaseService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .setValidator(validator)
            .build();
  }

  @Test
  void create_returnsKnowledgeBaseVo() throws Exception {
    KnowledgeBase kb = sampleKb(1L);
    when(knowledgeBaseService.create(any(CreateKbDTO.class))).thenReturn(kb);

    mockMvc
        .perform(
            post("/api/v1/kb")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"demo-kb\",\"description\":\"test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.id").value(1))
        .andExpect(jsonPath("$.data.name").value("demo-kb"));

    verify(knowledgeBaseService).create(any(CreateKbDTO.class));
  }

  @Test
  void listAll_returnsKnowledgeBaseList() throws Exception {
    KnowledgeBaseVO vo = KnowledgeBaseVO.fromEntity(sampleKb(2L));
    when(knowledgeBaseService.listAll()).thenReturn(List.of(vo));

    mockMvc
        .perform(get("/api/v1/kb"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data[0].id").value(2));
  }

  @Test
  void getById_returnsKnowledgeBase() throws Exception {
    KnowledgeBaseVO vo = KnowledgeBaseVO.fromEntity(sampleKb(3L));
    when(knowledgeBaseService.getById(3L)).thenReturn(vo);

    mockMvc
        .perform(get("/api/v1/kb/3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo-kb"));
  }

  @Test
  void update_returnsUpdatedKnowledgeBase() throws Exception {
    KnowledgeBase kb = sampleKb(4L);
    kb.setName("updated");
    when(knowledgeBaseService.update(eq(4L), any(UpdateKbDTO.class))).thenReturn(kb);

    mockMvc
        .perform(
            put("/api/v1/kb/4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("updated"));
  }

  @Test
  void delete_delegatesToService() throws Exception {
    mockMvc.perform(delete("/api/v1/kb/5")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

    verify(knowledgeBaseService).delete(5L);
  }

  private static KnowledgeBase sampleKb(long id) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setName("demo-kb");
    kb.setDescription("test");
    kb.setEmbeddingModel("text-embedding-v4");
    kb.setChunkSize(512);
    kb.setChunkOverlap(64);
    kb.setDocCount(0);
    kb.setChunkCount(0);
    kb.setStatus("active");
    kb.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
    kb.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
    return kb;
  }
}
