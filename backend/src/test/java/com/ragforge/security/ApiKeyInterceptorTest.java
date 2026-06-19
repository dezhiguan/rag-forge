package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.config.ApiKeyProperties;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.List;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ApiKeyInterceptorTest {

  @Mock private ApiKeyMapper apiKeyMapper;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private ApiKeyProperties apiKeyProperties;
  private ApiKeyInterceptor interceptor;

  @BeforeEach
  void setUp() {
    RagAuthContextHolder.clear();
    SecurityContextHolder.clearContext();
    apiKeyProperties = new ApiKeyProperties();
    interceptor =
        new ApiKeyInterceptor(apiKeyMapper, apiKeyProperties, new ObjectMapper(), redisTemplate, List.of());
  }

  private StringWriter stubResponseWriter() throws Exception {
    StringWriter body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    return body;
  }

  @Test
  void whitelistedPath_allowsWithoutApiKey() throws Exception {
    when(request.getRequestURI()).thenReturn("/api/v1/health");

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isTrue();
    verify(apiKeyMapper, never()).selectOne(any());
  }

  @Test
  void devKey_allowsOnlyWhenDevConfigPresent() throws Exception {
    interceptor =
        new ApiKeyInterceptor(
            apiKeyMapper, apiKeyProperties, new ObjectMapper(), redisTemplate, List.of(new DevApiKeyConfig()));
    when(request.getRequestURI()).thenReturn("/api/v1/search");
    when(request.getHeader("X-API-Key")).thenReturn("sk-ragforge-dev");

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isTrue();
    verify(apiKeyMapper, never()).selectOne(any());
    assertThat(RagAuthContextHolder.get()).isNotNull();
    assertThat(RagAuthContextHolder.get().principalType()).isEqualTo("service_account");
    interceptor.afterCompletion(request, response, new Object(), null);
    assertThat(RagAuthContextHolder.get()).isNull();
  }

  @Test
  void devKey_withoutDevConfigReturns401AndDoesNotUseDatabase() throws Exception {
    StringWriter responseBody = stubResponseWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/search");
    when(request.getHeader("X-API-Key")).thenReturn("sk-ragforge-dev");

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isFalse();
    verify(apiKeyMapper, never()).selectOne(any());
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(responseBody.toString()).contains("\"code\":401");
  }

  @Test
  void noApiKey_returns401EvenWhenDatabaseIsEmpty() throws Exception {
    StringWriter responseBody = stubResponseWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/search");

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isFalse();
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(apiKeyMapper, never()).selectCount(isNull());
    assertThat(responseBody.toString()).contains("\"code\":401");
  }

  @Test
  void invalidApiKey_returns401() throws Exception {
    StringWriter responseBody = stubResponseWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/search");
    when(request.getHeader("X-API-Key")).thenReturn("sk-invalid");
    when(apiKeyMapper.selectOne(any())).thenReturn(null);

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isFalse();
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(responseBody.toString()).contains("\"code\":401");
    assertThat(responseBody.toString()).contains("Invalid API Key");
  }

  @Test
  void validApiKeyOverRateLimit_returns429() throws Exception {
    StringWriter responseBody = stubResponseWriter();
    when(request.getRequestURI()).thenReturn("/api/v1/search");
    when(request.getHeader("X-API-Key")).thenReturn("sk-test");

    ApiKey keyRecord = new ApiKey();
    keyRecord.setApiKey("sk-test");
    keyRecord.setRateLimit(100);
    when(apiKeyMapper.selectOne(any())).thenReturn(keyRecord);

    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString())).thenReturn(101L);

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isFalse();
    verify(response).setStatus(429);
    assertThat(responseBody.toString()).contains("\"code\":429");
    assertThat(responseBody.toString()).contains("rate limit exceeded");
  }

  @Test
  void validApiKeyUnderRateLimit_allowsRequest() throws Exception {
    when(request.getRequestURI()).thenReturn("/api/v1/search");
    when(request.getHeader("X-API-Key")).thenReturn("sk-test");

    ApiKey keyRecord = new ApiKey();
    keyRecord.setApiKey("sk-test");
    keyRecord.setRateLimit(100);
    when(apiKeyMapper.selectOne(any())).thenReturn(keyRecord);

    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString())).thenReturn(1L);

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isTrue();
    assertThat(RagAuthContextHolder.get()).isNotNull();
    assertThat(RagAuthContextHolder.get().principalType()).isEqualTo("service_account");
    verify(response, never()).setStatus(eq(429));
    verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    interceptor.afterCompletion(request, response, new Object(), null);
    assertThat(RagAuthContextHolder.get()).isNull();
  }
}
