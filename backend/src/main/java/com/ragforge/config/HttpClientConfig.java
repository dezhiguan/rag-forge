package com.ragforge.config;

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

  /** 调试台 LLM 生成：DashScope chat 在云环境常需 10s+，单独放宽读超时。 */
  @Bean
  public RestTemplate llmRestTemplate(
      @Value("${app.dashscope.llm-timeout-ms:60000}") int llmTimeoutMs) {
    int connectSec = 5;
    int responseSec = Math.max(10, llmTimeoutMs / 1000);
    return buildRestTemplate(connectSec, responseSec);
  }

  private static RestTemplate buildRestTemplate(int connectTimeoutSec, int responseTimeoutSec) {
    PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(50);
    connectionManager.setDefaultMaxPerRoute(20);

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSec))
            .setResponseTimeout(Timeout.ofSeconds(responseTimeoutSec))
            .build();

    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .build();

    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory(httpClient);
    return new RestTemplate(factory);
  }
}
