package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.judge.JudgeMetricsAggregator;
import java.sql.Array;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminE2eJudgeControllerTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private JudgeMetricsAggregator judgeMetricsAggregator;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new AdminE2eJudgeController(jdbcTemplate, objectMapper, judgeMetricsAggregator))
            .setControllerAdvice(new com.ragforge.common.GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void ping_returnsOk() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/e2e/judge-result/_ping"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  @Test
  void clearJudgeResults_deletesByTenantId() throws Exception {
    when(jdbcTemplate.update(anyString(), eq("default"))).thenReturn(3);

    mockMvc
        .perform(delete("/api/v1/admin/e2e/judge-result"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.judgeResults").isNumber());
  }

  @Test
  void clearJudgeResults_customTenant() throws Exception {
    when(jdbcTemplate.update(anyString(), eq("test-tenant"))).thenReturn(1);

    mockMvc
        .perform(delete("/api/v1/admin/e2e/judge-result?tenantId=test-tenant"))
        .andExpect(status().isOk());
  }

  @Test
  void triggerAggregator_callsAggregate() throws Exception {
    doNothing().when(judgeMetricsAggregator).aggregate();

    mockMvc
        .perform(post("/api/v1/admin/e2e/judge-metrics/aggregate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ok"));

    verify(judgeMetricsAggregator).aggregate();
  }

  @Test
  void grantKbAccess_callsUpdate() throws Exception {
    when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);

    mockMvc
        .perform(
            post("/api/v1/admin/e2e/kb-acl/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kbId\":17,\"userId\":42,\"permission\":\"read\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));
  }

  @Test
  void grantKbAccess_defaultPermissionIsRead() throws Exception {
    when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);

    mockMvc
        .perform(
            post("/api/v1/admin/e2e/kb-acl/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kbId\":17,\"userId\":42}"))
        .andExpect(status().isOk());
  }

  @Test
  void revokeKbAccess_callsDeleteUpdate() throws Exception {
    when(jdbcTemplate.update(anyString(), any(Long.class), anyString())).thenReturn(1);

    mockMvc
        .perform(delete("/api/v1/admin/e2e/kb-acl/revoke?userId=42&kbId=17"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));
  }

  @Test
  void seedJudgeResult_missingKbIds_throwsIllegalArgument() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/e2e/judge-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"test\"}"))
        .andExpect(status().isInternalServerError());  // unhandled IllegalArgumentException → 500
  }

  @Test
  void seedJudgeResult_withKbIds_callsJdbcTemplate() throws Exception {
    Array mockArray = org.mockito.Mockito.mock(Array.class);
    when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
        .thenAnswer(inv -> {
          org.springframework.jdbc.core.ConnectionCallback<?> cb = inv.getArgument(0);
          Connection conn = org.mockito.Mockito.mock(Connection.class);
          when(conn.createArrayOf(anyString(), any())).thenReturn(mockArray);
          return cb.doInConnection(conn);
        });
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(7L)
        .thenReturn(3L);

    mockMvc
        .perform(
            post("/api/v1/admin/e2e/judge-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kbIds\":[17,18],\"tenantId\":\"e2e-test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.answerLogId").isNumber())
        .andExpect(jsonPath("$.data.judgeResultId").isNumber());
  }
}
