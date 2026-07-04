package com.ragforge.config;

import java.time.Duration;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

  /** 检索链路（Query Rewrite / Rerank）：保持较短超时，失败可降级。 */
  @Bean
  public RestTemplate restTemplate() {
    return buildRestTemplate(3, 8);
  }

  /**
   * 认证网关代理专用：client_assertion 为单次使用（网关按 jti 去重），绝不能自动重试——重试会复用同一
   * jti 被判 CLIENT_ASSERTION_REPLAYED，既偶发导致真实用户登录失败，又会掩盖 429 等真实响应。故禁用重试。
   * 另统一透传调用方真实 IP（X-Forwarded-For）：网关的发码/找回限流按 IP 计数，
   * 不透传则全部请求算在本服务出口 IP 上，一个共享桶会放大成全站限流。
   */
  @Bean("authRestTemplate")
  public RestTemplate authRestTemplate() {
    RestTemplate template =
        new RestTemplate(requestFactory(Duration.ofSeconds(3), Duration.ofSeconds(8), true));
    template.getInterceptors().add(HttpClientConfig::forwardClientIp);
    return template;
  }

  private static org.springframework.http.client.ClientHttpResponse forwardClientIp(
      org.springframework.http.HttpRequest request,
      byte[] body,
      org.springframework.http.client.ClientHttpRequestExecution execution)
      throws java.io.IOException {
    var attrs =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attrs
        instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
      var servletRequest = servletAttrs.getRequest();
      String forwarded = servletRequest.getHeader("X-Forwarded-For");
      // 入口 Nginx 已写入客户端 IP；无该头（如内网直连）时退回对端地址。只取第一跳。
      String clientIp =
          (forwarded != null && !forwarded.isBlank())
              ? forwarded.split(",")[0].trim()
              : servletRequest.getRemoteAddr();
      if (clientIp != null && !clientIp.isBlank()) {
        request.getHeaders().set("X-Forwarded-For", clientIp);
      }
    }
    return execution.execute(request, body);
  }

  /** 调试台 LLM 生成：DashScope chat 在云环境常需 10s+，单独放宽读超时。 */
  @Bean
  public RestTemplate llmRestTemplate(
      @Value("${app.dashscope.llm-timeout-ms:60000}") int llmTimeoutMs) {
    int connectSec = 5;
    int responseSec = Math.max(10, llmTimeoutMs / 1000);
    return buildRestTemplate(connectSec, responseSec);
  }

  /** LLM-as-Judge DeepSeek 调用：严格跟随 app.deepseek.timeout-ms，避免复用 DashScope 超时。 */
  @Bean("deepseekRestTemplate")
  public RestTemplate deepseekRestTemplate(
      @Value("${app.deepseek.timeout-ms:30000}") int timeoutMs) {
    Duration timeout = Duration.ofMillis(Math.max(1, timeoutMs));
    HttpComponentsClientHttpRequestFactory factory = requestFactory(timeout, timeout);
    return new RestTemplate(factory);
  }

  private static RestTemplate buildRestTemplate(int connectTimeoutSec, int responseTimeoutSec) {
    return new RestTemplate(
        requestFactory(
            Duration.ofSeconds(connectTimeoutSec),
            Duration.ofSeconds(responseTimeoutSec)));
  }

  static HttpComponentsClientHttpRequestFactory requestFactory(
      Duration connectTimeout, Duration responseTimeout) {
    return requestFactory(connectTimeout, responseTimeout, false);
  }

  static HttpComponentsClientHttpRequestFactory requestFactory(
      Duration connectTimeout, Duration responseTimeout, boolean disableRetries) {
    PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(50);
    connectionManager.setDefaultMaxPerRoute(20);

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
            .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
            .build();

    var httpClientBuilder =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig);
    if (disableRetries) {
      httpClientBuilder.disableAutomaticRetries();
    }
    CloseableHttpClient httpClient = httpClientBuilder.build();

    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory(httpClient);
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(responseTimeout);
    return factory;
  }
}
