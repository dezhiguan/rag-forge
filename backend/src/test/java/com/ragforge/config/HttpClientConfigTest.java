package com.ragforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class HttpClientConfigTest {

  @Test
  void deepseekRestTemplateUsesConfiguredTimeoutMs() {
    RestTemplate restTemplate = new HttpClientConfig().deepseekRestTemplate(5000);

    assertThat(restTemplate.getRequestFactory())
        .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    HttpComponentsClientHttpRequestFactory factory =
        (HttpComponentsClientHttpRequestFactory) restTemplate.getRequestFactory();
    assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(5000L);
    assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(5000L);
  }
}
