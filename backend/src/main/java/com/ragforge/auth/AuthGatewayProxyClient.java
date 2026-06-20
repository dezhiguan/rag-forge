package com.ragforge.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
public class AuthGatewayProxyClient {

  private final AuthProxyProperties properties;
  private final ClientAssertionFactory clientAssertionFactory;
  private final RestTemplate restTemplate;

  public AuthGatewayProxyClient(
      AuthProxyProperties properties,
      ClientAssertionFactory clientAssertionFactory,
      RestTemplate restTemplate) {
    this.properties = properties;
    this.clientAssertionFactory = clientAssertionFactory;
    this.restTemplate = restTemplate;
  }

  public TokenResponse loginPassword(String account, String password) {
    MultiValueMap<String, String> form = clientForm();
    form.add("account", account);
    form.add("password", password);
    form.add("target_aud", properties.getTargetAudience());
    return postForm("/auth/login/password", form, TokenResponse.class);
  }

  public TokenResponse loginMobile(String phone, String code) {
    MultiValueMap<String, String> form = clientForm();
    form.add("phone", phone);
    form.add("code", code);
    form.add("target_aud", properties.getTargetAudience());
    return postForm("/auth/login/mobile", form, TokenResponse.class);
  }

  public void sendSms(String phone, String scene) {
    postJson("/auth/sms/send", Map.of("phone", phone, "scene", scene), Map.class);
  }

  public Object resetInit(String account, String phone) {
    return postJson("/auth/password/reset/init", Map.of("account", account, "phone", phone), Object.class);
  }

  public Object resetVerify(String account, String phone, String code) {
    return postJson("/auth/password/reset/verify", Map.of("account", account, "phone", phone, "code", code), Object.class);
  }

  public TokenResponse resetConfirm(String resetTicket, String newPassword) {
    return postJson(
        "/auth/password/reset/confirm",
        Map.of(
            "reset_ticket", resetTicket,
            "new_password", newPassword,
            "target_aud", properties.getTargetAudience(),
            "client_id", properties.getClientId(),
            "client_assertion_type", ClientAssertionFactory.ASSERTION_TYPE,
            "client_assertion", clientAssertionFactory.create()),
        TokenResponse.class);
  }

  public TokenResponse refresh(String refreshToken) {
    MultiValueMap<String, String> form = clientForm();
    form.add("refresh_token", refreshToken);
    return postForm("/auth/token/refresh", form, TokenResponse.class);
  }

  public void logout(String authorization) {
    HttpHeaders headers = new HttpHeaders();
    if (authorization != null && !authorization.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    exchange("/auth/logout", new HttpEntity<>(headers), Map.class);
  }

  public void logoutAll(String authorization, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (authorization != null && !authorization.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    exchange("/auth/logout-all", new HttpEntity<>(Map.of("password", password), headers), Map.class);
  }

  public Object userinfo(String authorization) {
    HttpHeaders headers = new HttpHeaders();
    if (authorization != null && !authorization.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    return restTemplate.exchange(properties.getBaseUrl() + "/userinfo", org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(headers), Object.class).getBody();
  }

  private MultiValueMap<String, String> clientForm() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", properties.getClientId());
    form.add("client_assertion_type", ClientAssertionFactory.ASSERTION_TYPE);
    form.add("client_assertion", clientAssertionFactory.create());
    return form;
  }

  private <T> T postForm(String path, MultiValueMap<String, String> form, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    return exchange(path, new HttpEntity<>(form, headers), responseType);
  }

  private <T> T postJson(String path, Object body, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return exchange(path, new HttpEntity<>(body, headers), responseType);
  }

  private <T> T exchange(String path, HttpEntity<?> entity, Class<T> responseType) {
    try {
      ResponseEntity<T> response = restTemplate.postForEntity(properties.getBaseUrl() + path, entity, responseType);
      return response.getBody();
    } catch (HttpStatusCodeException ex) {
      throw new AuthProxyException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
    }
  }

  public static class AuthProxyException extends RuntimeException {
    private final HttpStatusCode status;
    private final String body;

    public AuthProxyException(HttpStatusCode status, String body, Throwable cause) {
      super("auth-gateway request failed", cause);
      this.status = status;
      this.body = body;
    }

    public HttpStatusCode status() {
      return status;
    }

    public String body() {
      return body;
    }
  }

  @Data
  public static class TokenResponse {
    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("expires_in")
    private long expiresIn;
  }
}
