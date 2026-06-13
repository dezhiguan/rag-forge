package com.ragforge.common;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.config.ApiKeyProperties;
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
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class ApiAndExceptionTest {

  @Mock private ApiKeyMapper apiKeyMapper;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private KnowledgeBaseService knowledgeBaseService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ApiKeyProperties apiKeyProperties = new ApiKeyProperties();
    apiKeyProperties.setWhitelistPaths(List.of("/api/v1/health"));

    ApiKeyInterceptor interceptor =
        new ApiKeyInterceptor(
            apiKeyMapper, apiKeyProperties, new ObjectMapper(), redisTemplate);

    mockMvc =
        standaloneSetup(new HealthController(), new KnowledgeBaseController(knowledgeBaseService))
            .addInterceptors(interceptor)
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void healthWithoutApiKeyReturns200() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.msg").value("success"));
  }

  @Test
  void kbWithoutApiKeyReturns401() throws Exception {
    when(apiKeyMapper.selectCount(isNull())).thenReturn(1L);

    mockMvc
        .perform(get("/api/v1/kb"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.msg").value("Invalid API Key"));
  }
}
