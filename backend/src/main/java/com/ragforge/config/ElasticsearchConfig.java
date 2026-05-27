package com.ragforge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {

  @Bean
  public RestClient elasticsearchRestClient(ElasticsearchProperties properties) {
    RestClientBuilder builder =
        RestClient.builder(new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme()));

    if (StringUtils.hasText(properties.getUsername()) && StringUtils.hasText(properties.getPassword())) {
      BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
      credentialsProvider.setCredentials(
          AuthScope.ANY,
          new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword()));
      builder.setHttpClientConfigCallback(
          httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
    }

    // RestClient doesn't connect eagerly; startup stays resilient even if ES is down.
    log.info(
        "Elasticsearch RestClient configured: {}://{}:{} (auth={})",
        properties.getScheme(),
        properties.getHost(),
        properties.getPort(),
        StringUtils.hasText(properties.getUsername()));

    return builder.build();
  }

  @Bean
  public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
    return new RestClientTransport(restClient, new JacksonJsonpMapper());
  }

  @Bean
  public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
    return new ElasticsearchClient(transport);
  }
}
