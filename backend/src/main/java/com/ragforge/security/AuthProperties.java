package com.ragforge.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.auth")
public class AuthProperties {

  private String issuer = "https://auth.careermate.cn";
  private String audience = "ragforge-admin-api";
  private String jwksUrl = "http://auth.careermate.cn/.well-known/jwks.json";
  private long jwksCacheTtlMs = 3_600_000L;
  private long clockSkewSeconds = 60L;
}
