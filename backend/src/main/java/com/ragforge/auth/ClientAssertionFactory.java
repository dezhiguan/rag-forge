package com.ragforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClientAssertionFactory {

  public static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

  private final AuthProxyProperties properties;
  private final ObjectMapper objectMapper;

  public ClientAssertionFactory(AuthProxyProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public String create() {
    try {
      long now = Instant.now().getEpochSecond();
      String header =
          base64Url(
              objectMapper.writeValueAsBytes(
                  Map.of("alg", "RS256", "typ", "JWT", "kid", properties.getClientAssertionKid())));
      String payload =
          base64Url(
              objectMapper.writeValueAsBytes(
                  Map.of(
                      "iss", properties.getClientId(),
                      "sub", properties.getClientId(),
                      "aud", properties.getTokenEndpointAudience(),
                      "jti", "ca_" + UUID.randomUUID(),
                      "iat", now,
                      "exp", now + 600)));
      String signingInput = header + "." + payload;
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(readPrivateKey(properties.getClientAssertionPrivateKey()));
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + base64Url(signature.sign());
    } catch (Exception ex) {
      throw new IllegalStateException("failed to create client_assertion", ex);
    }
  }

  private RSAPrivateKey readPrivateKey(String pem) throws Exception {
    if (!StringUtils.hasText(pem)) {
      throw new IllegalStateException("ragforge.auth.proxy.client-assertion-private-key is required");
    }
    String normalized =
        pem.replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(normalized);
    return (RSAPrivateKey)
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
