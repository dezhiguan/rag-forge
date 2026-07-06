package com.ragforge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 管理 Elasticsearch 客户端的生命周期，并提供「自愈」能力。
 *
 * <p>背景：底层 {@link RestClient} 用 Apache 异步 HttpClient，其 I/O reactor 线程一旦遇到未处理异常而终止，
 * 之后每个请求都会立即以 {@code "Request execution cancelled"} 失败，且**永不自恢复**——表现为 ES 集群明明健康，
 * 但所有写入/查询静默失败，只能靠重启 pod 恢复（2026-07-07 生产实测：worker 的 ES 客户端死掉导致一批文档
 * 全部 {@code ES 索引写入失败}）。
 *
 * <p>本类周期性对 ES 做轻量健康探测（{@code GET /}），连续失败达阈值即关闭旧客户端、重建新客户端，无需人工重启。
 * 同时为客户端配置连接/读超时与 keep-alive，降低 reactor 异常终止的概率。
 */
@Slf4j
@Component
public class ElasticsearchClientProvider {

  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int SOCKET_TIMEOUT_MS = 60_000;
  private static final long KEEP_ALIVE_MS = 60_000L;
  /** reactor 死后是 100% 失败，连续这么多次探测失败即重建（留一次容忍偶发抖动）。 */
  private static final int REBUILD_THRESHOLD = 2;

  private final ElasticsearchProperties properties;

  private volatile RestClient restClient;
  private volatile ElasticsearchTransport transport;
  private volatile ElasticsearchClient client;
  private volatile int consecutiveFailures = 0;

  public ElasticsearchClientProvider(ElasticsearchProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  public synchronized void init() {
    build();
  }

  /** 返回当前存活的客户端（重建后会指向新实例，调用方每次取用即可拿到 live client）。 */
  public ElasticsearchClient get() {
    ElasticsearchClient current = client;
    if (current == null) {
      synchronized (this) {
        if (client == null) {
          build();
        }
        current = client;
      }
    }
    return current;
  }

  private synchronized void build() {
    closeQuietly();
    RestClientBuilder builder =
        RestClient.builder(
                new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme()))
            .setRequestConfigCallback(
                rc -> rc.setConnectTimeout(CONNECT_TIMEOUT_MS).setSocketTimeout(SOCKET_TIMEOUT_MS))
            .setHttpClientConfigCallback(
                hc -> {
                  hc.setDefaultIOReactorConfig(
                      IOReactorConfig.custom().setSoKeepAlive(true).build());
                  hc.setKeepAliveStrategy((response, context) -> KEEP_ALIVE_MS);
                  if (StringUtils.hasText(properties.getUsername())
                      && StringUtils.hasText(properties.getPassword())) {
                    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(
                        AuthScope.ANY,
                        new UsernamePasswordCredentials(
                            properties.getUsername(), properties.getPassword()));
                    hc.setDefaultCredentialsProvider(credentialsProvider);
                  }
                  return hc;
                });

    // RestClient 不会立即连接；ES 宕机也不阻塞启动。
    this.restClient = builder.build();
    this.transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
    this.client = new ElasticsearchClient(transport);
    this.consecutiveFailures = 0;
    log.info(
        "Elasticsearch client built: {}://{}:{} (auth={}, connectTimeout={}ms, socketTimeout={}ms)",
        properties.getScheme(),
        properties.getHost(),
        properties.getPort(),
        StringUtils.hasText(properties.getUsername()),
        CONNECT_TIMEOUT_MS,
        SOCKET_TIMEOUT_MS);
  }

  /**
   * 周期性健康探测：走底层 {@link RestClient} 的 {@code GET /}。连续失败达阈值即重建客户端，
   * 从「reactor 已死、请求秒 cancel」的坏态中恢复。间隔可通过
   * {@code elasticsearch.health-check-interval-ms} 配置（默认 30s）。
   */
  @Scheduled(
      initialDelayString = "${elasticsearch.health-check-delay-ms:30000}",
      fixedDelayString = "${elasticsearch.health-check-interval-ms:30000}")
  public void healthCheck() {
    RestClient current = restClient;
    if (current == null) {
      rebuild("client not initialized");
      return;
    }
    try {
      current.performRequest(new Request("GET", "/"));
      if (consecutiveFailures > 0) {
        log.info("Elasticsearch health check recovered after {} failure(s)", consecutiveFailures);
      }
      consecutiveFailures = 0;
    } catch (Exception e) {
      consecutiveFailures++;
      log.warn(
          "Elasticsearch health check failed ({}/{}): {}",
          consecutiveFailures,
          REBUILD_THRESHOLD,
          e.getMessage());
      if (consecutiveFailures >= REBUILD_THRESHOLD) {
        rebuild("health check failed " + consecutiveFailures + " consecutive times");
      }
    }
  }

  private synchronized void rebuild(String reason) {
    log.warn("Rebuilding Elasticsearch client (reason: {})", reason);
    try {
      build();
    } catch (Exception e) {
      log.error("Failed to rebuild Elasticsearch client: {}", e.getMessage(), e);
    }
  }

  @PreDestroy
  public synchronized void closeQuietly() {
    try {
      if (transport != null) {
        transport.close();
      }
    } catch (Exception ignored) {
      // best effort
    }
    try {
      if (restClient != null) {
        restClient.close();
      }
    } catch (Exception ignored) {
      // best effort
    }
  }
}
