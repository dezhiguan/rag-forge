package com.ragforge.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.config.ApiKeyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.web.TraceResponseBodyAdvice;
import com.ragforge.web.filter.TraceFilter;
import com.ragforge.controller.HealthController;
import com.ragforge.controller.KnowledgeBaseController;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.service.KnowledgeBaseService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ExtendWith(MockitoExtension.class)
class ApiAndExceptionTest {

  @Mock private ApiKeyMapper apiKeyMapper;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private KnowledgeBaseService knowledgeBaseService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    RagAuthContextHolder.clear();
    SecurityContextHolder.clearContext();
    ApiKeyProperties apiKeyProperties = new ApiKeyProperties();
    apiKeyProperties.setWhitelistPaths(List.of("/api/v1/health"));

    ApiKeyInterceptor interceptor =
        new ApiKeyInterceptor(
            apiKeyMapper, apiKeyProperties, new ObjectMapper(), redisTemplate, List.of());

    mockMvc =
        standaloneSetup(new HealthController(), new KnowledgeBaseController(knowledgeBaseService))
            .addFilter(new TraceFilter("ragforge-backend"))
            .addInterceptors(interceptor)
            .setControllerAdvice(new TraceResponseBodyAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void healthWithoutApiKeyReturns200() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("success"))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(header().exists("X-Request-Id"))
            .andReturn();

    String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
    JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    org.junit.jupiter.api.Assertions.assertEquals(headerTraceId, body.get("traceId").asText());
  }

  @Test
  void kbWithoutApiKeyReturns401() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/kb"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.msg").value("Invalid API Key"))
            // 新契约：错误体不再携带 traceId，改由 X-Trace-Id 响应头承载。
            .andExpect(jsonPath("$.traceId").doesNotExist())
            .andExpect(header().exists("X-Trace-Id"))
            .andReturn();

    // 新契约：traceId 只在响应头，不在错误体。
    org.junit.jupiter.api.Assertions.assertNotNull(result.getResponse().getHeader("X-Trace-Id"));
    JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    org.junit.jupiter.api.Assertions.assertNull(body.get("traceId"));
  }
}
