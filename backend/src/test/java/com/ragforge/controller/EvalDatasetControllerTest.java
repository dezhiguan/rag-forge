package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.vo.EvalDatasetVO;
import com.ragforge.service.EvalDatasetService;
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
class EvalDatasetControllerTest {

  @Mock private EvalDatasetService evalDatasetService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        standaloneSetup(new EvalDatasetController(evalDatasetService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .setValidator(validator)
            .build();
  }

  @Test
  void listAll_returnsDatasets() throws Exception {
    EvalDatasetVO vo = new EvalDatasetVO();
    vo.setId(1L);
    vo.setName("ds-1");
    when(evalDatasetService.listAll()).thenReturn(List.of(vo));

    mockMvc
        .perform(get("/api/v1/eval/datasets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("ds-1"));
  }

  @Test
  void create_returnsDataset() throws Exception {
    EvalDatasetVO vo = new EvalDatasetVO();
    vo.setId(2L);
    vo.setName("new-ds");
    when(evalDatasetService.create(any(CreateEvalDatasetDTO.class))).thenReturn(vo);

    mockMvc
        .perform(
            post("/api/v1/eval/datasets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-ds\",\"kbId\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(2));
  }

  @Test
  void getById_returnsDataset() throws Exception {
    EvalDatasetVO vo = new EvalDatasetVO();
    vo.setId(3L);
    when(evalDatasetService.getById(3L)).thenReturn(vo);

    mockMvc.perform(get("/api/v1/eval/datasets/3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(3));
  }

  @Test
  void delete_delegatesToService() throws Exception {
    mockMvc.perform(delete("/api/v1/eval/datasets/4")).andExpect(status().isOk());

    verify(evalDatasetService).delete(4L);
  }
}
