package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JwksClientTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void getKeyLoadsAndCachesJwksByKid() throws Exception {
    RSAPublicKey publicKey = generatePublicKey();
    AtomicInteger requestCount = new AtomicInteger();
    startServer(jwksBody("kid-1", publicKey), requestCount);
    JwksClient client = clientWithUrl(serverUrl());

    RSAPublicKey first = client.getKey("kid-1");
    RSAPublicKey second = client.getKey("kid-1");

    assertThat(first.getModulus()).isEqualTo(publicKey.getModulus());
    assertThat(second).isSameAs(first);
    assertThat(requestCount).hasValue(1);
  }

  @Test
  void getKeyRefreshesOnceForUnknownKid() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    startServer(jwksBody("kid-1", generatePublicKey()), requestCount);
    JwksClient client = clientWithUrl(serverUrl());

    assertThatThrownBy(() -> client.getKey("kid-2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown JWT kid");

    assertThat(requestCount).hasValue(2);
  }

  @Test
  void getKeyWrapsInvalidJwksAsInfrastructureFailure() throws Exception {
    startServer("{}", new AtomicInteger());
    JwksClient client = clientWithUrl(serverUrl());

    assertThatThrownBy(() -> client.getKey("kid-1"))
        .isInstanceOf(JwtInfrastructureException.class)
        .hasMessageContaining("failed to refresh JWKS");
  }

  @Test
  void getKeyRejectsBlankKidBeforeFetching() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    startServer(jwksBody("kid-1", generatePublicKey()), requestCount);
    JwksClient client = clientWithUrl(serverUrl());

    assertThatThrownBy(() -> client.getKey(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JWT kid missing");
    assertThat(requestCount).hasValue(0);
  }

  private void startServer(String responseBody, AtomicInteger requestCount) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        exchange -> {
          requestCount.incrementAndGet();
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private JwksClient clientWithUrl(String jwksUrl) {
    AuthProperties properties = new AuthProperties();
    properties.setJwksUrl(jwksUrl);
    properties.setJwksCacheTtlMs(60_000L);
    return new JwksClient(properties, new ObjectMapper());
  }

  private String serverUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
  }

  private static RSAPublicKey generatePublicKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    KeyPair keyPair = generator.generateKeyPair();
    return (RSAPublicKey) keyPair.getPublic();
  }

  private static String jwksBody(String kid, RSAPublicKey publicKey) {
    return """
        {"keys":[{"kty":"RSA","kid":"%s","n":"%s","e":"%s"}]}
        """
        .formatted(kid, base64Url(publicKey.getModulus()), base64Url(publicKey.getPublicExponent()));
  }

  private static String base64Url(BigInteger value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray());
  }
}
