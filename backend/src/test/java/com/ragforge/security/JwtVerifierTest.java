package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
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
    lenient()
        .when(jwksClient.getKey("kid-1"))
        .thenReturn((java.security.interfaces.RSAPublicKey) keyPair.getPublic());
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

  @Test
  void malformedToken_wrongPartCount_throwsInvalidException() {
    assertThatThrownBy(() -> verifier.verify("not.a.valid.jwt.token"))
        .isInstanceOf(JwtInvalidException.class)
        .hasMessageContaining("invalid JWT format");
  }

  @Test
  void unsupportedAlgorithm_throwsInvalidException() throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> header = Map.of("alg", "HS256", "kid", "kid-1");
    Map<String, Object> claims = Map.of("iss", "issuer", "aud", "audience", "exp", now + 3600);
    String signingInput = base64Json(header) + "." + base64Json(claims);
    String fakeToken = signingInput + ".fakesig";

    assertThatThrownBy(() -> verifier.verify(fakeToken))
        .isInstanceOf(JwtInvalidException.class)
        .hasMessageContaining("unsupported JWT alg");
  }

  @Test
  void issuerMismatch_throwsInvalidException() throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "kid-1");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "wrong-issuer");
    claims.put("aud", "audience");
    claims.put("exp", now + 3600);
    String signingInput = base64Json(header) + "." + base64Json(claims);
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(keyPair.getPrivate());
    sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    String t = signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

    assertThatThrownBy(() -> verifier.verify(t))
        .isInstanceOf(JwtInvalidException.class)
        .hasMessageContaining("issuer mismatch");
  }

  @Test
  void audienceMismatch_throwsInvalidException() throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "kid-1");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "issuer");
    claims.put("aud", "wrong-audience");
    claims.put("exp", now + 3600);
    String signingInput = base64Json(header) + "." + base64Json(claims);
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(keyPair.getPrivate());
    sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    String t = signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

    assertThatThrownBy(() -> verifier.verify(t))
        .isInstanceOf(JwtInvalidException.class)
        .hasMessageContaining("audience mismatch");
  }

  @Test
  void invalidSignature_throwsInvalidException() throws Exception {
    long now = Instant.now().getEpochSecond();
    String goodToken = token(Map.of("exp", now + 3600));
    // Corrupt last character of signature
    String corrupted = goodToken.substring(0, goodToken.length() - 4) + "XXXX";

    assertThatThrownBy(() -> verifier.verify(corrupted))
        .isInstanceOf(JwtInvalidException.class);
  }

  @Test
  void validToken_returnsClaimsWithAllFields() throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> extraClaims = new LinkedHashMap<>();
    extraClaims.put("exp", now + 3600);
    extraClaims.put("sub", "user-42");
    extraClaims.put("user_id", 42L);

    JwtClaims claims = verifier.verify(token(extraClaims));

    assertThat(claims.longValue("user_id")).isEqualTo(42L);
    assertThat(claims.string("sub")).isEqualTo("user-42");
  }

  @Test
  void toContext_mapsClaimsToRagAuthContext() throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("exp", now + 3600);
    extra.put("user_id", 7L);
    extra.put("rag_role", "KB_EDITOR");
    extra.put("rag_readable_kb_ids", java.util.List.of(1, 2, 3));
    extra.put("rag_writable_kb_ids", java.util.List.of(2, 3));

    JwtClaims claims = verifier.verify(token(extra));
    RagAuthContext ctx = verifier.toContext(claims);

    assertThat(ctx.userId()).isEqualTo(7L);
    assertThat(ctx.ragRole()).isEqualTo("KB_EDITOR");
    assertThat(ctx.readableKbIds()).containsExactly(1L, 2L, 3L);
    assertThat(ctx.writableKbIds()).containsExactly(2L, 3L);
  }

  @Test
  void toContext_noRagRole_defaultsToKbViewer() throws Exception {
    long now = Instant.now().getEpochSecond();
    JwtClaims claims = verifier.verify(token(Map.of("exp", now + 3600, "user_id", 5L)));

    RagAuthContext ctx = verifier.toContext(claims);

    assertThat(ctx.ragRole()).isEqualTo("KB_VIEWER");
  }

  @Test
  void toContext_uidFallback_usedWhenUserIdAbsent() throws Exception {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("exp", now + 3600);
    extra.put("uid", 99L);
    JwtClaims claims = verifier.verify(token(extra));

    RagAuthContext ctx = verifier.toContext(claims);

    assertThat(ctx.userId()).isEqualTo(99L);
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
