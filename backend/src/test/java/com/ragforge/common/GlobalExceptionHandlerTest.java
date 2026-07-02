package com.ragforge.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
        .andExpect(status().isInternalServerError())
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
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void accessDeniedReturns403() throws Exception {
    mockMvc
        .perform(get("/test/forbidden"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void methodNotSupportedReturns405() throws Exception {
    // POST to a GET-only endpoint
    mockMvc
        .perform(post("/test/forbidden").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value(405));
  }

  @Test
  void unhandledExceptionReturns500() throws Exception {
    mockMvc
        .perform(get("/test/runtime"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500));
  }

  @Test
  void missingParamReturns400() throws Exception {
    mockMvc
        .perform(get("/test/param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        // M11：msg 为友好中文，机器码移至 errorCode。
        .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.containsString("MISSING_PARAM")));
  }

  @Test
  void typeMismatchReturns400() throws Exception {
    // /test/item/{id} expects Long; pass "NaN"
    mockMvc
        .perform(get("/test/item/NaN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        // M11：msg 为友好中文，机器码移至 errorCode。
        .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.containsString("INVALID_PARAM")));
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

    @GetMapping("/test/forbidden")
    public Result<Void> forbidden() {
      throw new AccessDeniedException("no access");
    }

    @GetMapping("/test/runtime")
    public Result<Void> runtime() {
      throw new RuntimeException("boom");
    }

    @GetMapping("/test/param")
    public Result<Void> param(@RequestParam String required) {
      return Result.ok();
    }

    @GetMapping("/test/item/{id}")
    public Result<Void> item(@PathVariable Long id) {
      return Result.ok();
    }
  }

  @Data
  static class ValidateRequest {
    @NotBlank(message = "name is required")
    private String name;
  }
}
