package com.ragforge.auth;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RagForgeClientJwksController {

  private final AuthProxyProperties properties;

  public RagForgeClientJwksController(AuthProxyProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/api/v1/.well-known/ragforge-admin-backend-jwks.json")
  public Map<String, Object> jwks() throws Exception {
    RSAPublicKey key = readPublicKey(properties.getPublicKeyPem());
    return Map.of(
        "keys",
        List.of(
            Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", properties.getClientAssertionKid(),
                "n", unsignedBase64Url(key.getModulus()),
                "e", unsignedBase64Url(key.getPublicExponent()))));
  }

  private RSAPublicKey readPublicKey(String pem) throws Exception {
    if (!StringUtils.hasText(pem)) {
      throw new IllegalStateException("ragforge.auth.proxy.public-key-pem is required");
    }
    String normalized =
        pem.replace("\\n", "\n")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(normalized);
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
  }

  private String unsignedBase64Url(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
