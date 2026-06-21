package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerService;
import com.ragforge.common.BizException;
import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.security.KbAccessGuard;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class AnswerControllerTest {

  @Mock private AnswerService answerService;
  @Mock private KbAccessGuard kbAccessGuard;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new AnswerController(answerService, kbAccessGuard))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void answerDisabledWithSseAccept_returns403Json() throws Exception {
    when(kbAccessGuard.filterReadable(any())).thenReturn(Set.of(109L));
    doThrow(new BizException(403, "ANSWER_DISABLED"))
        .when(answerService)
        .validateAnswerMode(any(AnswerRequest.class));

    mockMvc
        .perform(
            post("/api/v1/answer")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kbIds":[109],"query":"Java 技术栈","answerMode":"ON"}
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.msg").value("ANSWER_DISABLED"));
  }
}
