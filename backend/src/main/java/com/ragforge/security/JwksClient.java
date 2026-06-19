package com.ragforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class JwksClient {

  private final AuthProperties properties;
  private final ObjectMapper objectMapper;
  private final RestTemplate restTemplate = new RestTemplate();
  private final Map<String, RSAPublicKey> keys = new ConcurrentHashMap<>();
  private volatile long fetchedAtMs;

  public RSAPublicKey getKey(String kid) {
    if (!StringUtils.hasText(kid)) {
      throw new IllegalArgumentException("JWT kid missing");
    }
    refreshIfNeeded(false);
    RSAPublicKey key = keys.get(kid);
    if (key == null) {
      refreshIfNeeded(true);
      key = keys.get(kid);
    }
    if (key == null) {
      throw new IllegalArgumentException("unknown JWT kid");
    }
    return key;
  }

  private synchronized void refreshIfNeeded(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && !keys.isEmpty() && now - fetchedAtMs < properties.getJwksCacheTtlMs()) {
      return;
    }
    try {
      String body = restTemplate.getForObject(properties.getJwksUrl(), String.class);
      JsonNode jwks = objectMapper.readTree(body).path("keys");
      if (!jwks.isArray()) {
        throw new IllegalStateException("JWKS keys missing");
      }
      Map<String, RSAPublicKey> loaded = new ConcurrentHashMap<>();
      for (JsonNode node : jwks) {
        String kid = node.path("kid").asText();
        if (!StringUtils.hasText(kid)) {
          continue;
        }
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(node.path("n").asText()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(node.path("e").asText()));
        RSAPublicKey key =
            (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        loaded.put(kid, key);
      }
      keys.clear();
      keys.putAll(loaded);
      fetchedAtMs = now;
    } catch (Exception ex) {
      throw new JwtInfrastructureException("failed to refresh JWKS", ex);
    }
  }
}
