package com.ragforge.security;

import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevApiKeyConfig {

  static final String DEV_KEY = "sk-ragforge-dev";

  boolean matches(String apiKey) {
    return DEV_KEY.equals(apiKey);
  }

  RagAuthContext context() {
    return new RagAuthContext(
        null,
        "dev",
        "SERVICE_ACCOUNT",
        Set.of(),
        Set.of(),
        Set.of("rag:search"),
        "service_account",
        "sa:dev");
  }
}
