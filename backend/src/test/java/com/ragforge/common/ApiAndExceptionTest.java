package com.ragforge.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiAndExceptionTest {

  @Autowired private MockMvc mockMvc;

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
    mockMvc
        .perform(get("/api/v1/kb"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.msg").value("Invalid API Key"));
  }
}
