package com.ragforge.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.api-key")
public class ApiKeyProperties {

  private String header = "X-API-Key";

  private List<String> whitelistPaths = new ArrayList<>(List.of("/api/v1/health"));
}
