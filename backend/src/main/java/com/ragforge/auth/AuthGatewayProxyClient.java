package com.ragforge.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Component
public class AuthGatewayProxyClient {

  private final AuthProxyProperties properties;
  private final ClientAssertionFactory clientAssertionFactory;
  private final RestTemplate restTemplate;

  public AuthGatewayProxyClient(
      AuthProxyProperties properties,
      ClientAssertionFactory clientAssertionFactory,
      @Qualifier("authRestTemplate") RestTemplate restTemplate) {
    this.properties = properties;
    this.clientAssertionFactory = clientAssertionFactory;
    this.restTemplate = restTemplate;
  }

  public TokenResponse loginPassword(String account, String password, boolean remember) {
    return loginPassword(account, password, null, null, remember);
  }

  public TokenResponse loginPassword(
      String account, String password, String captcha, String challengeId, boolean remember) {
    MultiValueMap<String, String> form = clientForm();
    form.add("account", account);
    form.add("password", password);
    form.add("target_aud", properties.getTargetAudience());
    // 记住我：网关据此发 30 天 refresh token（否则默认 7 天）
    form.add("remember", String.valueOf(remember));
    // 登录失败次数过多时前端会带上图形验证码作答，透传给网关校验；空值不下发。
    if (StringUtils.hasText(captcha)) {
      form.add("captcha", captcha);
    }
    if (StringUtils.hasText(challengeId)) {
      form.add("challenge_id", challengeId);
    }
    return postForm("/auth/login/password", form, TokenResponse.class);
  }

  /** 换一张：获取一张新的图形验证码（公开，无需 client 鉴权）。返回 {captchaImage, challengeId}。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> getCaptcha() {
    return restTemplate.getForObject(properties.getBaseUrl() + "/auth/captcha", Map.class);
  }

  public TokenResponse loginMobile(String phone, String code, boolean remember) {
    MultiValueMap<String, String> form = clientForm();
    form.add("phone", phone);
    form.add("code", code);
    form.add("target_aud", properties.getTargetAudience());
    form.add("remember", String.valueOf(remember));
    return postForm("/auth/login/mobile", form, TokenResponse.class);
  }

  public void sendSms(String phone, String scene) {
    // 透传 app=ragforge：网关据此在登录场景下对未注册手机号拦截发码（避免白发短信）。
    // phone 为 null（如空请求体）时转成空串，避免 Map.of 拒绝 null 抛 NPE→500；空串由网关校验返回友好 400。
    postJson(
        "/auth/sms/send",
        Map.of("phone", phone == null ? "" : phone, "scene", scene, "app", "ragforge"),
        Map.class);
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

  /** 注册/补全（公开）。app 固定为 ragforge。返回 {userId, linked, message}。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> register(String phone, String smsCode, String username, String email, String password) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("phone", phone);
    body.put("smsCode", smsCode);
    body.put("username", username);
    body.put("email", email);
    body.put("password", password);
    body.put("app", "ragforge");
    return postJson("/auth/register", body, Map.class);
  }

  public void setPassword(String authorization, String oldPassword, String newPassword) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("oldPassword", oldPassword);
    body.put("newPassword", newPassword);
    bearerJson("/auth/credential/set-password", authorization, body);
  }

  public void bindEmail(String authorization, String email, String password) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("email", email);
    body.put("password", password);
    bearerJson("/auth/credential/bind-email", authorization, body);
  }

  public void setUsername(String authorization, String username) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("username", username);
    bearerJson("/auth/credential/set-username", authorization, body);
  }

  /** 递增目标用户 session_version，使其现存 token 在下次访问时失效（如被移出组织后调用）。 */
  @SuppressWarnings("unchecked")
  public void invalidateUserSession(Long userId) {
    MultiValueMap<String, String> form = clientForm();
    postForm("/internal/users/" + userId + "/invalidate-session", form, Map.class);
  }

  /** 申请注销账号（Bearer 保护，SMS OTP 已由前端收集）。返回 {deletionScheduledAt}。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> requestAccountDeletion(String authorization, String phone, String smsCode) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("phone", phone);
    body.put("smsCode", smsCode);
    return bearerJsonResult("/auth/users/me/deletion-request", authorization, body);
  }

  /** 撤销注销申请（冷静期内）。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> cancelAccountDeletion(String authorization, String phone, String smsCode) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    if (phone != null) body.put("phone", phone);
    if (smsCode != null) body.put("smsCode", smsCode);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (authorization != null && !authorization.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    try {
      ResponseEntity<Map> response = restTemplate.exchange(
          properties.getBaseUrl() + "/auth/users/me/deletion-request",
          org.springframework.http.HttpMethod.DELETE,
          new HttpEntity<>(body, headers),
          Map.class);
      return response.getBody();
    } catch (HttpStatusCodeException ex) {
      throw new AuthProxyException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
    }
  }

  /** 老用户补签协议。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> acceptTerms(String authorization, String termsVersion) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("termsVersion", termsVersion);
    return bearerJsonResult("/auth/users/me/terms-acceptance", authorization, body);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> bearerJsonResult(String path, String authorization, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (authorization != null && !authorization.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    return exchange(path, new HttpEntity<>(body, headers), Map.class);
  }

  /**
   * 按手机号精确解析用户（client 鉴权，内部接口）。 返回 {found, registered, authUserId, username,
   * maskedPhone}；未注册 found=false。
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> resolveByPhone(String phone) {
    MultiValueMap<String, String> form = clientForm();
    form.add("phone", phone);
    return postForm("/internal/users/resolve-by-phone", form, Map.class);
  }

  private void bearerJson(String path, String authorization, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (authorization != null && !authorization.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    exchange(path, new HttpEntity<>(body, headers), Map.class);
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

    /** refresh token 剩余生命周期（秒）。旧网关不返回该字段时为 0，代理层回退默认 7 天。 */
    @JsonProperty("refresh_expires_in")
    private long refreshExpiresIn;

    /** 登录时用户协议版本落后当前版本，需前端弹窗补签。 */
    @JsonProperty("terms_update_required")
    private boolean termsUpdateRequired;
  }
}
