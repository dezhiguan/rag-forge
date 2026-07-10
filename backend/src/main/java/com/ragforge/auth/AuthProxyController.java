package com.ragforge.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.auth.AuthGatewayProxyClient.AuthProxyException;
import com.ragforge.auth.AuthGatewayProxyClient.TokenResponse;
import com.ragforge.common.Result;
import com.ragforge.events.AuthEventService;
import com.ragforge.security.JwtClaims;
import com.ragforge.security.JwtVerifier;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
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
  private final UserProfileService userProfileService;
  private final JwtVerifier jwtVerifier;
  private final AuthEventService authEventService;
  private final JdbcTemplate jdbcTemplate;

  public AuthProxyController(
      AuthGatewayProxyClient client,
      AuthProxyProperties properties,
      ObjectMapper objectMapper,
      UserProfileService userProfileService,
      JwtVerifier jwtVerifier,
      AuthEventService authEventService,
      JdbcTemplate jdbcTemplate) {
    this.client = client;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.userProfileService = userProfileService;
    this.jwtVerifier = jwtVerifier;
    this.authEventService = authEventService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @PostMapping("/register")
  public Result<Map<String, Object>> register(@RequestBody RegisterRequest request) {
    Map<String, Object> result =
        client.register(request.phone(), request.smsCode(), request.username(), request.email(), request.password());
    Object userId = result.get("userId");
    if (userId instanceof Number num) {
      userProfileService.syncIdentity(num.longValue(), request.username(), request.email(), request.phone());
    }
    return Result.ok(result);
  }

  @PostMapping("/credential/set-password")
  public Result<Map<String, Object>> setPassword(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody SetPasswordRequest request) {
    client.setPassword(authorization, request.oldPassword(), request.newPassword());
    return Result.ok(Map.of("success", true));
  }

  @PostMapping("/credential/bind-email")
  public Result<Map<String, Object>> bindEmail(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody BindEmailRequest request) {
    client.bindEmail(authorization, request.email(), request.password());
    Long userId = userIdFromAuth(authorization);
    if (userId != null) {
      userProfileService.syncIdentity(userId, null, request.email(), null);
    }
    return Result.ok(Map.of("success", true));
  }

  @PostMapping("/credential/set-username")
  public Result<Map<String, Object>> setUsername(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody SetUsernameRequest request) {
    client.setUsername(authorization, request.username());
    Long userId = userIdFromAuth(authorization);
    if (userId != null) {
      userProfileService.syncIdentity(userId, request.username(), null, null);
    }
    return Result.ok(Map.of("success", true));
  }

  private void revokeBearerAccessToken(String authorization) {
    if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
      return;
    }
    String token = authorization.substring("Bearer ".length()).trim();
    try {
      JwtClaims claims = jwtVerifier.verify(token);
      authEventService.revokeAccessTokenJti(claims.string("jti"), claims.longValue("exp"));
    } catch (RuntimeException ex) {
      Map<String, Object> claims = parseClaims(token);
      Object jti = claims.get("jti");
      Object exp = claims.get("exp");
      if (jti != null) {
        Long expEpoch = exp instanceof Number number ? number.longValue() : null;
        authEventService.revokeAccessTokenJti(String.valueOf(jti), expEpoch);
      }
    }
  }

  private Long userIdFromAuth(String authorization) {
    if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
      return null;
    }
    Map<String, Object> claims = parseClaims(authorization.substring("Bearer ".length()).trim());
    Object userId = claims.get("user_id");
    return userId instanceof Number num ? num.longValue() : null;
  }

  @PostMapping("/login")
  public ResponseEntity<Result<Map<String, Object>>> login(@RequestBody PasswordLoginRequest request) {
    return loginResponse(
        client.loginPassword(
            request.account(),
            request.password(),
            request.captcha(),
            request.challengeId(),
            Boolean.TRUE.equals(request.remember())));
  }

  /** 换一张：前端"看不清"时获取一张新的图形验证码。返回 {captchaImage, challengeId}。 */
  @GetMapping("/captcha")
  public Result<Map<String, Object>> captcha() {
    return Result.ok(client.getCaptcha());
  }

  @PostMapping("/login-mobile")
  public ResponseEntity<Result<Map<String, Object>>> loginMobile(@RequestBody MobileLoginRequest request) {
    return loginResponse(
        client.loginMobile(request.phone(), request.code(), Boolean.TRUE.equals(request.remember())));
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
      revokeBearerAccessToken(authorization);
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
    if (tokens.isTermsUpdateRequired()) {
      data.put("termsUpdateRequired", true);
    }
    String csrf = "csrf_" + UUID.randomUUID();
    // cookie 生命周期跟随网关 refresh token TTL（记住我=30天/默认7天）；旧网关无该字段时回退 7 天。
    Duration cookieTtl =
        tokens.getRefreshExpiresIn() > 0 ? Duration.ofSeconds(tokens.getRefreshExpiresIn()) : Duration.ofDays(7);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.getRefreshToken(), cookieTtl).toString())
        .header(HttpHeaders.SET_COOKIE, csrfCookie(csrf, cookieTtl).toString())
        .body(Result.ok(data));
  }

  private Map<String, Object> userProfile(Map<String, Object> claims) {
    Object userId = claims.get("user_id");
    Long authUserId = userId instanceof Number num ? num.longValue() : null;
    String account = userId == null ? "RAGForge 用户" : "user:" + userId;
    // 显示名兜底：显示名 > 用户名 > 脱敏手机号 > 用户_{id}，避免把内部标识 user:N 当显示名暴露。
    String displayName =
        authUserId == null
            ? account
            : userProfileService.resolveDisplayName(
                userProfileService.getOrCreate(authUserId), authUserId);
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("id", userId);
    user.put("account", account);
    user.put("displayName", displayName);
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

  private ResponseCookie refreshCookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(maxAge)
        .build();
  }

  private ResponseCookie csrfCookie(String value, Duration maxAge) {
    return ResponseCookie.from(CSRF_COOKIE, value)
        .httpOnly(false)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
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

  /** 协议补签（老用户弹出弹窗后调用）。 */
  @PostMapping("/users/me/terms-acceptance")
  public Result<Map<String, Object>> acceptTerms(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody TermsAcceptRequest request) {
    String version = request.termsVersion() != null ? request.termsVersion() : "1.0";
    return Result.ok(client.acceptTerms(authorization, version));
  }

  /** 申请注销账号。先在 rag-forge 侧检查唯一管理员约束，再委托网关执行注销和 SMS 校验。 */
  @PostMapping("/users/me/deletion-request")
  public Result<Map<String, Object>> requestDeletion(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody DeletionRequestPayload request) {
    Long userId = userIdFromAuth(authorization);
    if (userId != null) {
      checkNotSoleAdmin(userId);
    }
    return Result.ok(client.requestAccountDeletion(authorization, request.phone(), request.smsCode()));
  }

  /** 撤销注销申请（冷静期内）。 */
  @DeleteMapping("/users/me/deletion-request")
  public Result<Map<String, Object>> cancelDeletion(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) DeletionRequestPayload request) {
    String phone = request != null ? request.phone() : null;
    String smsCode = request != null ? request.smsCode() : null;
    return Result.ok(client.cancelAccountDeletion(authorization, phone, smsCode));
  }

  private void checkNotSoleAdmin(Long userId) {
    List<Map<String, Object>> soleOwnerOrgs = jdbcTemplate.queryForList("""
        SELECT o.id, o.name FROM organizations o
        JOIN org_members om ON om.org_id = o.id
        WHERE om.user_id = ? AND om.role = 'OWNER'
        AND (SELECT COUNT(*) FROM org_members om2
             WHERE om2.org_id = o.id AND om2.role = 'OWNER') = 1
        """, userId);
    if (!soleOwnerOrgs.isEmpty()) {
      List<String> names = soleOwnerOrgs.stream()
          .map(o -> String.valueOf(o.getOrDefault("name", "未知组织")))
          .collect(Collectors.toList());
      throw new AuthProxyException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "{\"error\":\"SOLE_ADMIN\",\"message\":\"您是以下组织的唯一管理员，请先移交权限后再申请注销："
              + String.join("、", names) + "\"}",
          null);
    }
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

  public record PasswordLoginRequest(
      String account, String password, String captcha, String challengeId, Boolean remember) {}

  public record MobileLoginRequest(String phone, String code, Boolean remember) {}

  public record SmsRequest(String phone, String scene) {}

  public record ResetInitRequest(String account, String phone) {}

  public record ResetVerifyRequest(String account, String phone, String code) {}

  public record ResetConfirmRequest(String account, @com.fasterxml.jackson.annotation.JsonProperty("reset_ticket") String resetTicket,
      @com.fasterxml.jackson.annotation.JsonProperty("new_password") String newPassword) {}

  public record LogoutAllRequest(String password) {}

  public record RegisterRequest(String phone, String smsCode, String username, String email, String password) {}

  public record SetPasswordRequest(String oldPassword, String newPassword) {}

  public record BindEmailRequest(String email, String password) {}

  public record SetUsernameRequest(String username) {}

  public record TermsAcceptRequest(String termsVersion) {}

  public record DeletionRequestPayload(String phone, String smsCode) {}
}
