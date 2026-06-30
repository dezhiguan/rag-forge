package com.ragforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ClientAssertionFactoryTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void create_generatesSignedJwtWithExpectedClaims() throws Exception {
    AuthProxyProperties properties = new AuthProxyProperties();
    properties.setClientId("ragforge-client");
    properties.setClientAssertionKid("kid-1");
    properties.setTokenEndpointAudience("https://auth.example.test/oauth/token");
    properties.setClientAssertionPrivateKey(pemPrivateKey());
    ClientAssertionFactory factory = new ClientAssertionFactory(properties, objectMapper);

    long before = Instant.now().getEpochSecond();
    String jwt = factory.create();
    long after = Instant.now().getEpochSecond();

    String[] parts = jwt.split("\\.");
    assertThat(parts).hasSize(3);
    JsonNode header = decodeJson(parts[0]);
    JsonNode payload = decodeJson(parts[1]);
    assertThat(header.path("alg").asText()).isEqualTo("RS256");
    assertThat(header.path("typ").asText()).isEqualTo("JWT");
    assertThat(header.path("kid").asText()).isEqualTo("kid-1");
    assertThat(payload.path("iss").asText()).isEqualTo("ragforge-client");
    assertThat(payload.path("sub").asText()).isEqualTo("ragforge-client");
    assertThat(payload.path("aud").asText()).isEqualTo("https://auth.example.test/oauth/token");
    assertThat(payload.path("jti").asText()).startsWith("ca_");
    assertThat(payload.path("iat").asLong()).isBetween(before, after);
    assertThat(payload.path("exp").asLong()).isBetween(before + 600, after + 600);
    assertThat(parts[2]).isNotBlank();
  }

  @Test
  void create_missingPrivateKeyThrowsIllegalState() {
    AuthProxyProperties properties = new AuthProxyProperties();
    ClientAssertionFactory factory = new ClientAssertionFactory(properties, objectMapper);

    assertThatThrownBy(factory::create)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to create client_assertion");
  }

  private JsonNode decodeJson(String base64Url) throws Exception {
    return objectMapper.readTree(Base64.getUrlDecoder().decode(base64Url));
  }

  private static String pemPrivateKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
  }
}
