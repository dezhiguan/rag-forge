package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.judge.SamplingUpsertRequest;
import com.ragforge.mapper.JudgeSamplingConfigMapper;
import com.ragforge.model.entity.JudgeSamplingConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class JudgeSamplingControllerTest {

  @Mock private JudgeSamplingConfigMapper configMapper;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new JudgeSamplingController(configMapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void upsert_rejectsHighRateWithoutConfirmation() throws Exception {
    SamplingUpsertRequest req = request(new BigDecimal("0.15"), false);

    mockMvc
        .perform(
            post("/api/v1/evaluation/quality/sampling")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.msg").value("SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM"));
  }

  @Test
  void upsert_acceptsHighRateWithConfirmation() throws Exception {
    when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(configMapper.insert(any(JudgeSamplingConfig.class))).thenReturn(1);
    SamplingUpsertRequest req = request(new BigDecimal("0.15"), true);

    mockMvc
        .perform(
            post("/api/v1/evaluation/quality/sampling")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.sampleRate").value(0.15));
  }

  @Test
  void samplingController_isAdminOnly() {
    PreAuthorize annotation = JudgeSamplingController.class.getAnnotation(PreAuthorize.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
  }

  private SamplingUpsertRequest request(BigDecimal sampleRate, boolean confirmed) {
    SamplingUpsertRequest req = new SamplingUpsertRequest();
    req.setScopeType("GLOBAL");
    req.setSampleRate(sampleRate);
    req.setEnabled(true);
    req.setConfirmed(confirmed);
    return req;
  }
}
