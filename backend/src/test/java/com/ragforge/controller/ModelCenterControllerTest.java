package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.modelcenter.ModelCenterService;
import com.ragforge.modelcenter.vo.CostDetailVo;
import com.ragforge.modelcenter.vo.CostStatsVo;
import com.ragforge.modelcenter.vo.ModelItemVo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class ModelCenterControllerTest {

  @Mock private ModelCenterService modelCenterService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new ModelCenterController(modelCenterService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void list_returnsModelItemVoList() throws Exception {
    ModelItemVo item = new ModelItemVo("qwen-plus", "千问Plus", "DashScope", "ANSWER",
        new BigDecimal("0.004"), new BigDecimal("0.012"), false, true, true, new BigDecimal("1.23"));
    when(modelCenterService.listModels()).thenReturn(List.of(item));

    mockMvc
        .perform(get("/api/v1/models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].code").value("qwen-plus"))
        .andExpect(jsonPath("$.data[0].vendor").value("DashScope"));
  }

  @Test
  void costStats_defaultDays_returnsStats() throws Exception {
    CostStatsVo.Kpi kpi = new CostStatsVo.Kpi(5, 4, new BigDecimal("10.50"), 100000L, 50000L, 200L, 0.98);
    CostStatsVo stats = new CostStatsVo(kpi, List.of(), List.of());
    when(modelCenterService.costStats(7)).thenReturn(stats);

    mockMvc
        .perform(get("/api/v1/models/cost/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kpi.modelCount").value(5))
        .andExpect(jsonPath("$.data.kpi.enabledCount").value(4));
  }

  @Test
  void costStats_customDays_passesParamToService() throws Exception {
    CostStatsVo.Kpi kpi = new CostStatsVo.Kpi(2, 2, BigDecimal.ZERO, 0L, 0L, 0L, 1.0);
    CostStatsVo stats = new CostStatsVo(kpi, List.of(), List.of());
    when(modelCenterService.costStats(30)).thenReturn(stats);

    mockMvc
        .perform(get("/api/v1/models/cost/stats?days=30"))
        .andExpect(status().isOk());
  }

  @Test
  void costDetail_returnsList() throws Exception {
    CostDetailVo detail = new CostDetailVo("ANSWER", "qwen-plus", 100L, 50000L, 20000L, new BigDecimal("1.50"), 350L);
    when(modelCenterService.costDetail()).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/v1/models/cost/detail"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].purpose").value("ANSWER"));
  }

  @Test
  void toggle_withEnabledTrue_returnsUpdatedState() throws Exception {
    when(modelCenterService.toggle("qwen-plus", true)).thenReturn(true);

    mockMvc
        .perform(
            put("/api/v1/models/qwen-plus/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.code").value("qwen-plus"))
        .andExpect(jsonPath("$.data.enabled").value(true));
  }

  @Test
  void toggle_withoutEnabledField_returns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/models/qwen-plus/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"foo\":\"bar\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void toggle_nullBody_returns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/models/qwen-plus/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
