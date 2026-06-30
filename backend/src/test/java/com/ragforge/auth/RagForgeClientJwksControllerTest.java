package com.ragforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagForgeClientJwksControllerTest {

  @Test
  void jwks_returnsRsaPublicKeyMetadata() throws Exception {
    AuthProxyProperties properties = new AuthProxyProperties();
    properties.setClientAssertionKid("kid-1");
    properties.setPublicKeyPem(publicKeyPem());
    RagForgeClientJwksController controller = new RagForgeClientJwksController(properties);

    Map<String, Object> jwks = controller.jwks();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
    assertThat(keys).hasSize(1);
    assertThat(keys.get(0))
        .containsEntry("kty", "RSA")
        .containsEntry("use", "sig")
        .containsEntry("alg", "RS256")
        .containsEntry("kid", "kid-1");
    assertThat(keys.get(0).get("n")).isInstanceOf(String.class);
    assertThat(keys.get(0).get("e")).isInstanceOf(String.class);
  }

  @Test
  void jwks_missingPublicKeyThrowsIllegalState() {
    RagForgeClientJwksController controller = new RagForgeClientJwksController(new AuthProxyProperties());

    assertThatThrownBy(controller::jwks)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("public-key-pem is required");
  }

  private static String publicKeyPem() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
  }
}
