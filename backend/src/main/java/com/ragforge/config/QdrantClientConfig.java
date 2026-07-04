package com.ragforge.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Qdrant gRPC 客户端 Bean。连接参数来自 {@link QdrantProperties}，API Key 由 env 注入。 */
@Slf4j
@Configuration
public class QdrantClientConfig {

  @Bean(destroyMethod = "close")
  public QdrantClient qdrantClient(QdrantProperties props) {
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(props.getHost(), props.getGrpcPort(), props.isUseTls())
            .withTimeout(Duration.ofMillis(props.getTimeoutMs()));
    if (StringUtils.hasText(props.getApiKey())) {
      builder.withApiKey(props.getApiKey());
    } else {
      // 生产必须配 API Key；缺失仅告警不阻断本地/测试环境。
      log.warn("Qdrant API Key 未配置，仅本地/测试可接受，生产环境请通过 env 注入 QDRANT_API_KEY");
    }
    log.info(
        "Qdrant client 初始化: host={} grpcPort={} collection={} dim={}",
        props.getHost(),
        props.getGrpcPort(),
        props.getCollection(),
        props.getVectorDim());
    return new QdrantClient(builder.build());
  }
}
