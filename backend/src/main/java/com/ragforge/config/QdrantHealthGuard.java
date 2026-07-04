package com.ragforge.config;

import io.qdrant.client.QdrantClient;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启动时校验 Qdrant 连通性与目标 collection 存在，尽早暴露配置/部署问题。 测试环境（profile=test）跳过，避免依赖真实 Qdrant。
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class QdrantHealthGuard {

  private final QdrantClient qdrantClient;
  private final QdrantProperties props;

  @PostConstruct
  public void verifyQdrant() {
    try {
      Boolean exists =
          qdrantClient
              .collectionExistsAsync(props.getCollection(), Duration.ofMillis(props.getTimeoutMs()))
              .get();
      if (exists == null || !exists) {
        throw new IllegalStateException(
            "Qdrant collection 不存在: "
                + props.getCollection()
                + "，请先执行部署脚本创建集合（见 docs/dev 迁移设计稿附录 A）");
      }
      log.info(
          "Qdrant health check passed: host={} collection={} dim={}",
          props.getHost(),
          props.getCollection(),
          props.getVectorDim());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Qdrant 连接校验被中断", e);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "无法连接 Qdrant(" + props.getHost() + ":" + props.getGrpcPort() + ")，请检查部署与网络", e);
    }
  }
}
