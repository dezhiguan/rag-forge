package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchProperties {

  /** Default to localhost:9200 with no auth. */
  private String host = "localhost";

  private int port = 9200;

  private String scheme = "http";

  private String username = "";

  private String password = "";
}
