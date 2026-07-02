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
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class JudgeSamplingControllerTest {

  @Mock private JudgeSamplingConfigMapper configMapper;
  @Mock private KbAccessGuard kbAccessGuard;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // 现有用例校验的是配置逻辑,以平台管理员身份运行,绕过新的权限拆分门槛。
    RagAuthContextHolder.set(
        new RagAuthContext(1L, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", "user-1"));
    mockMvc =
        standaloneSetup(new JudgeSamplingController(configMapper, kbAccessGuard))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
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
  void globalConfig_nonAdmin_forbidden() throws Exception {
    RagAuthContextHolder.set(
        new RagAuthContext(2L, "USER", Set.of(), Set.of(), Set.of(), "USER", "user-2"));
    mockMvc
        .perform(
            post("/api/v1/evaluation/quality/sampling")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(new BigDecimal("0.05"), true))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.msg").value("SAMPLING_GLOBAL_ADMIN_ONLY"));
  }

  @Test
  void kbOverride_byKbOrgAdmin_allowed() throws Exception {
    RagAuthContextHolder.set(
        new RagAuthContext(2L, "USER", Set.of(), Set.of(), Set.of(), "USER", "user-2"));
    when(kbAccessGuard.canAdmin(77L)).thenReturn(true);
    when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(configMapper.insert(any(JudgeSamplingConfig.class))).thenReturn(1);

    SamplingUpsertRequest req = new SamplingUpsertRequest();
    req.setScopeType("KB");
    req.setScopeId(77L);
    req.setSampleRate(new BigDecimal("0.05"));
    req.setEnabled(true);
    req.setConfirmed(true);

    mockMvc
        .perform(
            post("/api/v1/evaluation/quality/sampling")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
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
