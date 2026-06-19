package com.ragforge.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtVerifier {

  private final AuthProperties properties;
  private final JwksClient jwksClient;
  private final ObjectMapper objectMapper;

  public JwtClaims verify(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        throw new JwtInvalidException("invalid JWT format");
      }
      Map<String, Object> header = readJson(parts[0]);
      if (!"RS256".equals(header.get("alg"))) {
        throw new JwtInvalidException("unsupported JWT alg");
      }
      RSAPublicKey key = jwksClient.getKey(String.valueOf(header.get("kid")));
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initVerify(key);
      signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
        throw new JwtInvalidException("JWT signature invalid");
      }
      JwtClaims claims = new JwtClaims(readJson(parts[1]));
      if (!properties.getIssuer().equals(claims.string("iss"))) {
        throw new JwtInvalidException("JWT issuer mismatch");
      }
      if (!claims.audienceContains(properties.getAudience())) {
        throw new JwtInvalidException("JWT audience mismatch");
      }
      long nowEpochSeconds = Instant.now().getEpochSecond();
      long leewaySeconds = Math.max(60L, properties.getClockSkewSeconds());
      Long exp = claims.longValue("exp");
      if (exp == null || exp + leewaySeconds < nowEpochSeconds) {
        throw new JwtInvalidException("JWT expired");
      }
      Long notBefore = claims.longValue("nbf");
      if (notBefore != null && notBefore > nowEpochSeconds + leewaySeconds) {
        throw new JwtInvalidException("JWT not valid yet");
      }
      return claims;
    } catch (JwtInvalidException | IllegalArgumentException ex) {
      throw ex;
    } catch (JwtInfrastructureException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new JwtInvalidException("JWT verification failed", ex);
    }
  }

  public RagAuthContext toContext(JwtClaims claims) {
    String principalType = claims.string("principal_type");
    if (principalType == null) {
      principalType = "user";
    }
    Long userId = claims.longValue("user_id");
    if (userId == null) {
      userId = claims.longValue("uid");
    }
    return new RagAuthContext(
        userId,
        claims.string("tenant_id"),
        defaultString(claims.string("rag_role"), "KB_VIEWER"),
        longSet(claims.values().get("rag_readable_kb_ids")),
        longSet(claims.values().get("rag_writable_kb_ids")),
        scopes(claims),
        principalType,
        claims.string("sub"));
  }

  private Map<String, Object> readJson(String base64Url) throws Exception {
    byte[] json = Base64.getUrlDecoder().decode(base64Url);
    return objectMapper.readValue(json, new TypeReference<>() {});
  }

  private Set<Long> longSet(Object value) {
    Set<Long> result = new LinkedHashSet<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Number number) {
          result.add(number.longValue());
        } else if (item != null) {
          result.add(Long.valueOf(String.valueOf(item)));
        }
      }
    }
    return result;
  }

  private Set<String> stringSet(Object value) {
    Set<String> result = new LinkedHashSet<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          result.add(String.valueOf(item));
        }
      }
    }
    return result;
  }

  private Set<String> scopes(JwtClaims claims) {
    Set<String> result = stringSet(claims.values().get("scopes"));
    String scope = claims.string("scope");
    if (scope != null) {
      for (String item : scope.split("\\s+")) {
        if (!item.isBlank()) {
          result.add(item);
        }
      }
    }
    return result;
  }

  private String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
