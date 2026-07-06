package com.ragforge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端装配。
 *
 * <p>RestClient/Transport/Client 的构建、超时/keep-alive 配置以及**运行期自愈（reactor 死掉后重建）**
 * 都收敛到 {@link ElasticsearchClientProvider}。生产代码应通过 provider 取用 live client；这里仅保留一个
 * {@code ElasticsearchClient} Bean 供上下文装配/测试，快照自 provider（重建对已注入该 Bean 的消费者不可见，
 * 故新代码请注入 {@link ElasticsearchClientProvider}）。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {

  @Bean
  public ElasticsearchClient elasticsearchClient(ElasticsearchClientProvider provider) {
    return provider.get();
  }
}
