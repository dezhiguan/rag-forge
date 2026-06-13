package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.service.LlmService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

class LlmControllerTest {

  @Test
  void generate_returnsLlmResult() throws Exception {
    LlmService llmService =
        request ->
            Map.of(
                "content",
                "answer",
                "model",
                "qwen-plus",
                "totalMs",
                10L,
                "promptTokens",
                1,
                "completionTokens",
                2,
                "totalTokens",
                3);

    MockMvc mockMvc =
        standaloneSetup(new LlmController(llmService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    mockMvc
        .perform(
            post("/api/v1/llm/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"messages":[{"role":"user","content":"hi"}],"model":"qwen-plus"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.content").value("answer"))
        .andExpect(jsonPath("$.data.model").value("qwen-plus"));
  }
}
