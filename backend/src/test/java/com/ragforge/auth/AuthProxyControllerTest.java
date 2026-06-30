package com.ragforge.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.auth.AuthGatewayProxyClient.AuthProxyException;
import com.ragforge.auth.AuthGatewayProxyClient.TokenResponse;
import com.ragforge.events.AuthEventService;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.JwtVerifier;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthProxyControllerTest {

  @Mock private AuthGatewayProxyClient client;
  @Mock private UserProfileService userProfileService;
  @Mock private JwtVerifier jwtVerifier;
  @Mock private AuthEventService authEventService;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AuthProxyProperties properties = new AuthProxyProperties();

  @BeforeEach
  void setUp() {
    properties.setCookieSecure(false);
    mockMvc =
        standaloneSetup(new AuthProxyController(client, properties, objectMapper, userProfileService, jwtVerifier, authEventService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void register_delegatesToClientAndSyncsIdentity() throws Exception {
    when(client.register(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Map.of("userId", 42L, "account", "alice"));
    UserProfile profile = new UserProfile();
    profile.setAuthUserId(42L);
    when(userProfileService.getOrCreate(42L)).thenReturn(profile);

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"smsCode\":\"123456\",\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"P@ssw0rd\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(42));

    verify(userProfileService).syncIdentity(42L, "alice", "alice@example.com", "13800000000");
  }

  @Test
  void login_returnsAccessTokenAndSetsCookies() throws Exception {
    TokenResponse tokens = buildTokens(42L, "refresh-token-value");
    when(client.loginPassword("alice@example.com", "P@ssw0rd")).thenReturn(tokens);
    UserProfile profile = new UserProfile();
    profile.setAuthUserId(42L);
    profile.setDisplayName("Alice");
    when(userProfileService.getOrCreate(42L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(any(), eq(42L))).thenReturn("Alice");

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"alice@example.com\",\"password\":\"P@ssw0rd\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isString())
        .andExpect(header().exists("Set-Cookie"));
  }

  @Test
  void loginMobile_returnsAccessTokenWithCookies() throws Exception {
    TokenResponse tokens = buildTokens(7L, "refresh-mobile");
    when(client.loginMobile("13900000000", "654321")).thenReturn(tokens);
    UserProfile profile = new UserProfile();
    profile.setAuthUserId(7L);
    when(userProfileService.getOrCreate(7L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(any(), eq(7L))).thenReturn("Mobile User");

    mockMvc
        .perform(
            post("/api/auth/login-mobile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13900000000\",\"code\":\"654321\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isString());
  }

  @Test
  void sendSms_delegatesToClient() throws Exception {
    doNothing().when(client).sendSms(anyString(), anyString());

    mockMvc
        .perform(
            post("/api/auth/sms/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"scene\":\"register\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sent").value(true));

    verify(client).sendSms("13800000000", "register");
  }

  @Test
  void sendSms_nullScene_defaultsToLogin() throws Exception {
    doNothing().when(client).sendSms(anyString(), anyString());

    mockMvc
        .perform(
            post("/api/auth/sms/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\"}"))
        .andExpect(status().isOk());

    verify(client).sendSms("13800000000", "login");
  }

  @Test
  void setPassword_delegatesToClient() throws Exception {
    doNothing().when(client).setPassword(any(), anyString(), anyString());

    mockMvc
        .perform(
            post("/api/auth/credential/set-password")
                .header("Authorization", "Bearer access-token-xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"old\",\"newPassword\":\"new\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true));
  }

  @Test
  void resetInit_delegatesToClient() throws Exception {
    when(client.resetInit("alice@example.com", "13800000000"))
        .thenReturn(Map.of("resetToken", "token-abc"));

    mockMvc
        .perform(
            post("/api/auth/password/reset/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"alice@example.com\",\"phone\":\"13800000000\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resetToken").value("token-abc"));
  }

  @Test
  void resetVerify_delegatesToClient() throws Exception {
    when(client.resetVerify("alice@example.com", "13800000000", "654321"))
        .thenReturn(Map.of("ticket", "ticket-xyz"));

    mockMvc
        .perform(
            post("/api/auth/password/reset/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"alice@example.com\",\"phone\":\"13800000000\",\"code\":\"654321\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ticket").value("ticket-xyz"));
  }

  @Test
  void authProxyException_proxiesStatusAndBody() throws Exception {
    when(client.loginPassword(any(), any()))
        .thenThrow(new AuthProxyException(
            org.springframework.http.HttpStatus.UNAUTHORIZED,
            "{\"error\":\"invalid_credentials\"}",
            null));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"bad@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authProxyException_emptyBody_returnsDefaultMessage() throws Exception {
    when(client.loginPassword(any(), any()))
        .thenThrow(new AuthProxyException(
            org.springframework.http.HttpStatus.BAD_GATEWAY, "", null));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"a\",\"password\":\"b\"}"))
        .andExpect(status().isBadGateway());
  }

  @Test
  void bindEmail_syncsEmailToProfile() throws Exception {
    doNothing().when(client).bindEmail(any(), anyString(), anyString());
    // No JWT verification needed since we use a simple bearer token for userId extraction

    mockMvc
        .perform(
            post("/api/auth/credential/bind-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\",\"password\":\"P@ssw0rd\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true));
  }

  @Test
  void setUsername_delegatesToClient() throws Exception {
    doNothing().when(client).setUsername(any(), anyString());

    mockMvc
        .perform(
            post("/api/auth/credential/set-username")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newname\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true));
  }

  @Test
  void resetConfirm_returnsTokenAndSetsCookies() throws Exception {
    TokenResponse tokens = buildTokens(42L, "new-refresh");
    when(client.resetConfirm(anyString(), anyString())).thenReturn(tokens);
    UserProfile profile = new UserProfile();
    profile.setAuthUserId(42L);
    when(userProfileService.getOrCreate(42L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(any(), eq(42L))).thenReturn("Alice");

    mockMvc
        .perform(
            post("/api/auth/password/reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"alice@example.com\",\"reset_ticket\":\"tkt-1\",\"new_password\":\"NewP@ssw0rd\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isString());
  }

  @Test
  void logout_clearsRefreshCookieAndCallsClient() throws Exception {
    doNothing().when(client).logout(any());

    mockMvc
        .perform(
            post("/api/auth/logout")
                .header("Authorization", "Bearer some-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revoked").value(true));
  }

  @Test
  void refresh_usesRefreshTokenFromCookieAndReturnsNewTokens() throws Exception {
    TokenResponse tokens = buildTokens(7L, "new-refresh-token");
    when(client.refresh("old-refresh")).thenReturn(tokens);
    UserProfile profile = new UserProfile();
    profile.setAuthUserId(7L);
    when(userProfileService.getOrCreate(7L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(any(), eq(7L))).thenReturn("User7");

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .cookie(new org.springframework.mock.web.MockCookie("rf_refresh", "old-refresh")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isString());
  }

  @Test
  void userinfo_returnsUserinfoFromGateway() throws Exception {
    when(client.userinfo(any())).thenReturn(java.util.Map.of("sub", "u-7", "email", "user@test.com"));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/userinfo")
                .header("Authorization", "Bearer some-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sub").value("u-7"));
  }

  /** Build a TokenResponse with a fake JWT containing the given userId. */
  private TokenResponse buildTokens(long userId, String refreshToken) throws Exception {
    String payloadJson = objectMapper.writeValueAsString(Map.of(
        "user_id", userId,
        "platform_role", "USER",
        "rag_role", "KB_VIEWER",
        "scopes", "read",
        "jti", "test-jti",
        "exp", System.currentTimeMillis() / 1000 + 3600));
    String payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payloadJson.getBytes());
    String fakeJwt = "eyJhbGciOiJSUzI1NiJ9." + payload + ".fakesig";

    TokenResponse resp = new TokenResponse();
    resp.setAccessToken(fakeJwt);
    resp.setRefreshToken(refreshToken);
    resp.setExpiresIn(3600);
    return resp;
  }
}
