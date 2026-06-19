package com.ragforge.security;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.auth")
public class AuthProperties {

  private String issuer = "https://auth.careermate.cn";
  private List<String> audiences = List.of("ragforge-admin-api", "ragforge-search-api");
  private String jwksUrl = "http://auth.careermate.cn/.well-known/jwks.json";
  private long jwksCacheTtlMs = 3_600_000L;
  private long clockSkewSeconds = 60L;

  public void setAudience(String audience) {
    if (audience != null && !audience.isBlank()) {
      this.audiences = List.of(audience);
    }
  }
}
