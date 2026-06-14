package com.ragforge.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.web.TraceResponseBodyAdvice;
import com.ragforge.web.filter.TraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class HealthControllerTest {

  @Test
  void health_returnsOkWithTraceId() throws Exception {
    MockMvc mockMvc =
        standaloneSetup(new HealthController())
            .addFilter(new TraceFilter("ragforge-backend"))
            .setControllerAdvice(new TraceResponseBodyAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").value("ok"))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(header().exists("X-Request-Id"))
            .andReturn();

    String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
    JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    org.junit.jupiter.api.Assertions.assertEquals(headerTraceId, body.get("traceId").asText());
  }
}
