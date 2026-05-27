package com.ragforge.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .setValidator(validator)
            .build();
  }

  @Test
  void bizExceptionReturns500() throws Exception {
    mockMvc
        .perform(get("/test/biz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.msg").value("错误"));
  }

  @Test
  void validationFailureReturns400() throws Exception {
    mockMvc
        .perform(
            post("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(400));
  }

  @RestController
  static class TestController {

    @GetMapping("/test/biz")
    public Result<Void> biz() {
      throw new BizException("错误");
    }

    @PostMapping("/test/validate")
    public Result<Void> validate(@RequestBody @jakarta.validation.Valid ValidateRequest request) {
      return Result.ok();
    }
  }

  @Data
  static class ValidateRequest {
    @NotBlank(message = "name is required")
    private String name;
  }
}
