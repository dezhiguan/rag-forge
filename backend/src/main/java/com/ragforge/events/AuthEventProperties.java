package com.ragforge.events;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.auth.events")
public class AuthEventProperties {

  private String hmacSecret = "";
  private String signatureHeader = "X-Auth-Event-Signature";
  private String timestampHeader = "X-Auth-Event-Timestamp";
  private Duration idempotencyTtl = Duration.ofDays(7);
  private Duration revokedJtiTtl = Duration.ofDays(7);
  private Duration userRevocationTtl = Duration.ofDays(30);
  private Duration maxClockSkew = Duration.ofSeconds(300);
}
