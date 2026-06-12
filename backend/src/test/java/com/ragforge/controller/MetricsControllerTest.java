package com.ragforge.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.model.vo.DashboardMetricsVO;
import com.ragforge.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class MetricsControllerTest {

  @Mock private MetricsService metricsService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new MetricsController(metricsService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void dashboard_returnsMetrics() throws Exception {
    DashboardMetricsVO vo = new DashboardMetricsVO();
    vo.setKbCount(2);
    vo.setDocumentCount(5);
    when(metricsService.dashboard()).thenReturn(vo);

    mockMvc
        .perform(get("/api/v1/metrics/dashboard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.kbCount").value(2))
        .andExpect(jsonPath("$.data.documentCount").value(5));
  }
}
