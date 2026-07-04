package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Qdrant 向量库连接与检索参数。承载「向量存储 + ANN 检索」层。 */
@Data
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {

  /** 数据机内网地址。 */
  private String host = "172.25.90.183";

  /** gRPC 端口。 */
  private int grpcPort = 6334;

  /** 是否使用 TLS（内网默认关闭）。 */
  private boolean useTls = false;

  /** API Key，由环境变量注入，禁止硬编码。 */
  private String apiKey = "";

  /** 集合名。 */
  private String collection = "ragforge_chunks";

  /** 向量维度（与 embedding 输出一致）。 */
  private int vectorDim = 1024;

  /** 单次操作超时（毫秒）。 */
  private long timeoutMs = 10000;

  /** INT8 量化下 rescore 的候选放大倍数，保召回。 */
  private double oversampling = 2.0;
}
