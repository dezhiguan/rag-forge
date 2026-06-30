package com.ragforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.auth.AuthGatewayProxyClient.AuthProxyException;
import com.ragforge.auth.AuthGatewayProxyClient.TokenResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class AuthGatewayProxyClientTest {

  @Mock private ClientAssertionFactory clientAssertionFactory;
  @Mock private RestTemplate restTemplate;

  private AuthProxyProperties properties;
  private AuthGatewayProxyClient client;

  @BeforeEach
  void setUp() {
    properties = new AuthProxyProperties();
    properties.setBaseUrl("https://auth.example.test");
    properties.setClientId("ragforge-client");
    properties.setTargetAudience("ragforge-api");
    lenient().when(clientAssertionFactory.create()).thenReturn("assertion.jwt");
    client = new AuthGatewayProxyClient(properties, clientAssertionFactory, restTemplate);
  }

  @Test
  void loginPassword_postsClientFormAndTargetAudience() {
    TokenResponse token = token("access-1");
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/login/password"), any(HttpEntity.class), eq(TokenResponse.class)))
        .thenReturn(ResponseEntity.ok(token));

    TokenResponse result = client.loginPassword("amy", "secret");

    assertThat(result.getAccessToken()).isEqualTo("access-1");
    HttpEntity<?> entity = capturedPostEntity("/auth/login/password", TokenResponse.class);
    assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
    @SuppressWarnings("unchecked")
    MultiValueMap<String, String> form = (MultiValueMap<String, String>) entity.getBody();
    assertThat(form.toSingleValueMap())
        .containsEntry("client_id", "ragforge-client")
        .containsEntry("client_assertion_type", ClientAssertionFactory.ASSERTION_TYPE)
        .containsEntry("client_assertion", "assertion.jwt")
        .containsEntry("account", "amy")
        .containsEntry("password", "secret")
        .containsEntry("target_aud", "ragforge-api");
  }

  @Test
  void resetConfirm_postsClientAssertionJson() {
    TokenResponse token = token("reset-access");
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/password/reset/confirm"),
            any(HttpEntity.class),
            eq(TokenResponse.class)))
        .thenReturn(ResponseEntity.ok(token));

    TokenResponse result = client.resetConfirm("ticket-1", "new-pass");

    assertThat(result.getAccessToken()).isEqualTo("reset-access");
    HttpEntity<?> entity = capturedPostEntity("/auth/password/reset/confirm", TokenResponse.class);
    assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) entity.getBody();
    assertThat(body)
        .containsEntry("reset_ticket", "ticket-1")
        .containsEntry("new_password", "new-pass")
        .containsEntry("target_aud", "ragforge-api")
        .containsEntry("client_id", "ragforge-client")
        .containsEntry("client_assertion_type", ClientAssertionFactory.ASSERTION_TYPE)
        .containsEntry("client_assertion", "assertion.jwt");
  }

  @Test
  void logoutAll_postsBearerJsonBody() {
    when(restTemplate.postForEntity(eq("https://auth.example.test/auth/logout-all"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

    client.logoutAll("Bearer token", "secret");

    HttpEntity<?> entity = capturedPostEntity("/auth/logout-all", Map.class);
    assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
    assertThat(entity.getBody()).isEqualTo(Map.of("password", "secret"));
  }

  @Test
  void userinfo_usesGetAndOptionalAuthorizationHeader() {
    when(restTemplate.exchange(
            eq("https://auth.example.test/userinfo"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
        .thenReturn(ResponseEntity.ok(Map.of("sub", "u1")));

    Object result = client.userinfo("Bearer token");

    assertThat(result).isEqualTo(Map.of("sub", "u1"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(eq("https://auth.example.test/userinfo"), eq(HttpMethod.GET), entityCaptor.capture(), eq(Object.class));
    assertThat(entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
  }

  @Test
  void postForm_httpStatusExceptionIsWrapped() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/token/refresh"), any(HttpEntity.class), eq(TokenResponse.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                "bad token".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(() -> client.refresh("refresh-1"))
        .isInstanceOfSatisfying(
            AuthProxyException.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(ex.body()).isEqualTo("bad token");
            });
  }

  @Test
  void loginMobile_postsPhoneAndCode() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/login/mobile"), any(HttpEntity.class), eq(TokenResponse.class)))
        .thenReturn(ResponseEntity.ok(token("mobile-token")));

    TokenResponse result = client.loginMobile("13900000000", "654321");

    assertThat(result.getAccessToken()).isEqualTo("mobile-token");
    HttpEntity<?> entity = capturedPostEntity("/auth/login/mobile", TokenResponse.class);
    @SuppressWarnings("unchecked")
    org.springframework.util.MultiValueMap<String, String> form =
        (org.springframework.util.MultiValueMap<String, String>) entity.getBody();
    assertThat(form.toSingleValueMap())
        .containsEntry("phone", "13900000000")
        .containsEntry("code", "654321")
        .containsEntry("target_aud", "ragforge-api");
  }

  @Test
  void sendSms_postsJsonBody() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/sms/send"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

    client.sendSms("13800000000", "register");

    HttpEntity<?> entity = capturedPostEntity("/auth/sms/send", Map.class);
    assertThat(entity.getHeaders().getContentType())
        .isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) entity.getBody();
    assertThat(body)
        .containsEntry("phone", "13800000000")
        .containsEntry("scene", "register")
        .containsEntry("app", "ragforge");
  }

  @Test
  void resetInit_postsAccountAndPhone() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/password/reset/init"), any(HttpEntity.class), eq(Object.class)))
        .thenReturn(ResponseEntity.ok(Map.of("resetToken", "tok")));

    Object result = client.resetInit("alice@example.com", "13800000000");

    assertThat(result).isNotNull();
  }

  @Test
  void resetVerify_postsAllFields() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/password/reset/verify"), any(HttpEntity.class), eq(Object.class)))
        .thenReturn(ResponseEntity.ok(Map.of("ticket", "tkt-1")));

    Object result = client.resetVerify("alice@example.com", "13800000000", "123456");

    assertThat(result).isNotNull();
  }

  @Test
  void register_postsAllRegistrationFields() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/register"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("userId", 42L)));

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        client.register("13800000000", "123456", "alice", "alice@example.com", "P@ssw0rd");

    assertThat(result).containsKey("userId");
  }

  @Test
  void logout_postsBearerToken() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/logout"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null));

    client.logout("Bearer some-access-token");

    HttpEntity<?> entity = capturedPostEntity("/auth/logout", Map.class);
    assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer some-access-token");
  }

  @Test
  void setPassword_postsBearerAndPasswordFields() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/credential/set-password"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null));

    client.setPassword("Bearer token-xyz", "old123", "new456");

    HttpEntity<?> entity = capturedPostEntity("/auth/credential/set-password", Map.class);
    assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-xyz");
  }

  @Test
  void bindEmail_postsBearerAndEmailAndPassword() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/credential/bind-email"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null));

    client.bindEmail("Bearer tok", "new@example.com", "P@ssw0rd");

    HttpEntity<?> entity = capturedPostEntity("/auth/credential/bind-email", Map.class);
    assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok");
  }

  @Test
  void setUsername_postsBearerAndUsername() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/auth/credential/set-username"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null));

    client.setUsername("Bearer tok", "newusername");

    HttpEntity<?> entity = capturedPostEntity("/auth/credential/set-username", Map.class);
    assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok");
  }

  @Test
  void resolveByPhone_returnsUserData() {
    when(restTemplate.postForEntity(
            eq("https://auth.example.test/internal/users/resolve-by-phone"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("userId", 7L, "phone", "13800000000")));

    @SuppressWarnings("unchecked")
    Map<String, Object> result = client.resolveByPhone("13800000000");

    assertThat(result).containsKey("userId");
    HttpEntity<?> entity = capturedPostEntity("/internal/users/resolve-by-phone", Map.class);
    @SuppressWarnings("unchecked")
    org.springframework.util.MultiValueMap<String, String> form =
        (org.springframework.util.MultiValueMap<String, String>) entity.getBody();
    assertThat(form.toSingleValueMap()).containsEntry("phone", "13800000000");
  }

  private HttpEntity<?> capturedPostEntity(String path, Class<?> responseType) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate).postForEntity(eq("https://auth.example.test" + path), entityCaptor.capture(), eq(responseType));
    return entityCaptor.getValue();
  }

  private static TokenResponse token(String accessToken) {
    TokenResponse token = new TokenResponse();
    token.setAccessToken(accessToken);
    token.setRefreshToken("refresh");
    token.setTokenType("Bearer");
    token.setExpiresIn(3600);
    return token;
  }
}
