package com.ragforge.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.auth.AuthGatewayProxyClient.AuthProxyException;
import com.ragforge.auth.AuthGatewayProxyClient.TokenResponse;
import com.ragforge.common.Result;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthProxyController {

  private static final String REFRESH_COOKIE = "rf_refresh";
  private static final String CSRF_COOKIE = "rf_csrf";

  private final AuthGatewayProxyClient client;
  private final AuthProxyProperties properties;
  private final ObjectMapper objectMapper;

  public AuthProxyController(
      AuthGatewayProxyClient client, AuthProxyProperties properties, ObjectMapper objectMapper) {
    this.client = client;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/login")
  public ResponseEntity<Result<Map<String, Object>>> login(@RequestBody PasswordLoginRequest request) {
    return loginResponse(client.loginPassword(request.account(), request.password()));
  }

  @PostMapping("/login-mobile")
  public ResponseEntity<Result<Map<String, Object>>> loginMobile(@RequestBody MobileLoginRequest request) {
    return loginResponse(client.loginMobile(request.phone(), request.code()));
  }

  @PostMapping("/sms/send")
  public Result<Map<String, Object>> sendSms(@RequestBody SmsRequest request) {
    client.sendSms(request.phone(), StringUtils.hasText(request.scene()) ? request.scene() : "login");
    return Result.ok(Map.of("sent", true));
  }

  @PostMapping("/password/reset/init")
  public Result<Object> resetInit(@RequestBody ResetInitRequest request) {
    return Result.ok(client.resetInit(request.account(), request.phone()));
  }

  @PostMapping("/password/reset/verify")
  public Result<Object> resetVerify(@RequestBody ResetVerifyRequest request) {
    return Result.ok(client.resetVerify(request.account(), request.phone(), request.code()));
  }

  @PostMapping("/password/reset/confirm")
  public ResponseEntity<Result<Map<String, Object>>> resetConfirm(@RequestBody ResetConfirmRequest request) {
    return loginResponse(client.resetConfirm(request.resetTicket(), request.newPassword()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<Result<Map<String, Object>>> refresh(HttpServletRequest request) {
    String refreshToken = cookieValue(request, REFRESH_COOKIE);
    if (!StringUtils.hasText(refreshToken)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(401, "Refresh token missing"));
    }
    return loginResponse(client.refresh(refreshToken));
  }

  @PostMapping("/logout")
  public ResponseEntity<Result<Map<String, Object>>> logout(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    if (StringUtils.hasText(authorization)) {
      client.logout(authorization);
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_COOKIE).toString())
        .header(HttpHeaders.SET_COOKIE, clearCookie(CSRF_COOKIE).toString())
        .body(Result.ok(Map.of("revoked", true)));
  }

  @PostMapping("/logout-all")
  public ResponseEntity<Result<Map<String, Object>>> logoutAll(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody LogoutAllRequest request) {
    client.logoutAll(authorization, request.password());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_COOKIE).toString())
        .header(HttpHeaders.SET_COOKIE, clearCookie(CSRF_COOKIE).toString())
        .body(Result.ok(Map.of("revoked", true)));
  }

  @GetMapping("/userinfo")
  public Result<Object> userinfo(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    return Result.ok(client.userinfo(authorization));
  }

  private ResponseEntity<Result<Map<String, Object>>> loginResponse(TokenResponse tokens) {
    Map<String, Object> claims = parseClaims(tokens.getAccessToken());
    Map<String, Object> user = userProfile(claims);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("accessToken", tokens.getAccessToken());
    data.put("expiresIn", tokens.getExpiresIn());
    data.put("user", user);
    String csrf = "csrf_" + UUID.randomUUID();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.getRefreshToken()).toString())
        .header(HttpHeaders.SET_COOKIE, csrfCookie(csrf).toString())
        .body(Result.ok(data));
  }

  private Map<String, Object> userProfile(Map<String, Object> claims) {
    Object userId = claims.get("user_id");
    String account = userId == null ? "RAGForge 用户" : "user:" + userId;
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("id", userId);
    user.put("account", account);
    user.put("displayName", account);
    user.put("tenantId", claims.get("tenant_id"));
    user.put("tenantSlug", claims.get("tenant_id"));
    user.put("platformRole", claims.get("platform_role"));
    user.put("ragRole", claims.get("rag_role"));
    user.put("scopes", claims.get("scopes"));
    return user;
  }

  private Map<String, Object> parseClaims(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        return Map.of();
      }
      byte[] json = Base64.getUrlDecoder().decode(parts[1]);
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private ResponseCookie refreshCookie(String value) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(Duration.ofDays(7))
        .build();
  }

  private ResponseCookie csrfCookie(String value) {
    return ResponseCookie.from(CSRF_COOKIE, value)
        .httpOnly(false)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ofDays(7))
        .build();
  }

  private ResponseCookie clearCookie(String name) {
    return ResponseCookie.from(name, "")
        .httpOnly(REFRESH_COOKIE.equals(name))
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path(REFRESH_COOKIE.equals(name) ? "/api/auth" : "/")
        .maxAge(Duration.ZERO)
        .build();
  }

  private String cookieValue(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  @ExceptionHandler(AuthProxyException.class)
  public ResponseEntity<String> handleAuthProxy(AuthProxyException ex) {
    return ResponseEntity.status(ex.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(StringUtils.hasText(ex.body()) ? ex.body() : "{\"message\":\"认证服务请求失败\"}");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleException(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Result.fail(500, "认证代理不可用"));
  }

  public record PasswordLoginRequest(String account, String password, String captcha, String challengeId) {}

  public record MobileLoginRequest(String phone, String code) {}

  public record SmsRequest(String phone, String scene) {}

  public record ResetInitRequest(String account, String phone) {}

  public record ResetVerifyRequest(String account, String phone, String code) {}

  public record ResetConfirmRequest(String account, @com.fasterxml.jackson.annotation.JsonProperty("reset_ticket") String resetTicket,
      @com.fasterxml.jackson.annotation.JsonProperty("new_password") String newPassword) {}

  public record LogoutAllRequest(String password) {}
}
