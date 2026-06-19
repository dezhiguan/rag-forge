package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JwtVerifierTest {

  @Mock private JwksClient jwksClient;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private AuthProperties properties;
  private KeyPair keyPair;
  private JwtVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    properties = new AuthProperties();
    properties.setIssuer("issuer");
    properties.setAudience("audience");
    properties.setClockSkewSeconds(60);
    keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    when(jwksClient.getKey("kid-1")).thenReturn((java.security.interfaces.RSAPublicKey) keyPair.getPublic());
    verifier = new JwtVerifier(properties, jwksClient, objectMapper);
  }

  @Test
  void nbfInFutureIsRejected() throws Exception {
    long now = Instant.now().getEpochSecond();

    assertThatThrownBy(() -> verifier.verify(token(Map.of("exp", now + 3600, "nbf", now + 120))))
        .isInstanceOf(JwtInvalidException.class)
        .hasMessageContaining("not valid yet");
  }

  @Test
  void expThirtySecondsAgoWithinLeewayIsAccepted() throws Exception {
    long now = Instant.now().getEpochSecond();

    JwtClaims claims = verifier.verify(token(Map.of("exp", now - 30)));

    assertThat(claims.longValue("exp")).isEqualTo(now - 30);
  }

  @Test
  void expOneHundredTwentySecondsAgoBeyondLeewayIsRejected() throws Exception {
    long now = Instant.now().getEpochSecond();

    assertThatThrownBy(() -> verifier.verify(token(Map.of("exp", now - 120))))
        .isInstanceOf(JwtInvalidException.class)
        .hasMessageContaining("expired");
  }

  private String token(Map<String, Object> customClaims) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "kid-1");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "issuer");
    claims.put("aud", "audience");
    claims.putAll(customClaims);
    String signingInput = base64Json(header) + "." + base64Json(claims);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyPair.getPrivate());
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
  }

  private String base64Json(Map<String, Object> value) throws Exception {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
  }
}
