package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.events.AuthEventService;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  private JwtVerifier jwtVerifier;
  private AuthEventService authEventService;
  private AdminAccessAuditService adminAccessAuditService;
  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    RagAuthContextHolder.clear();
    AdminOverrideHolder.clear();
    OrgContextHolder.clear();
    SecurityContextHolder.clearContext();
    jwtVerifier = mock(JwtVerifier.class);
    authEventService = mock(AuthEventService.class);
    adminAccessAuditService = mock(AdminAccessAuditService.class);
    filter =
        new JwtAuthenticationFilter(
            jwtVerifier, new ObjectMapper(), authEventService, adminAccessAuditService);
  }

  @Test
  void missingBearerTokenSkipsAuthenticationAndClearsContexts() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Basic abc");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          RagAuthContextHolder.set(new RagAuthContext(1L, "USER", Set.of(), Set.of(), Set.of(), "USER", "u"));
          OrgContextHolder.set(16L);
          AdminOverrideHolder.activate("test");
        };

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(RagAuthContextHolder.get()).isNull();
    assertThat(OrgContextHolder.get()).isNull();
    assertThat(AdminOverrideHolder.isActive()).isFalse();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(jwtVerifier, never()).verify(any());
  }

  @Test
  void revokedJwtReturns401BeforeContextIsInstalled() throws Exception {
    String token = "header.payload.signature";
    JwtClaims claims =
        new JwtClaims(
            Map.of(
                "jti", "jti-1",
                "user_id", 42,
                "iat", 100,
                "rag_role", "ADMIN",
                "sub", "42"));
    when(jwtVerifier.verify(token)).thenReturn(claims);
    when(authEventService.isJwtRevoked(org.mockito.ArgumentMatchers.any())).thenReturn(true);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(RagAuthContextHolder.get()).isNull();
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void invalidJwtReturns401AndDoesNotInvokeChain() throws Exception {
    String token = "bad.jwt";
    when(jwtVerifier.verify(token)).thenThrow(new JwtInvalidException("bad token"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    // 令牌失效改为友好中文 + 机器码（errorCode=UNAUTHORIZED），不再是英文 "Unauthorized"。
    assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void jwtInfrastructureFailureReturns503() throws Exception {
    String token = "infra.jwt";
    when(jwtVerifier.verify(token)).thenThrow(new JwtInfrastructureException("jwks down", null));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("Service unavailable");
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void redisFailureDuringRevocationCheckContinuesWithVerifiedToken() throws Exception {
    String token = "header.payload.signature";
    JwtClaims claims = new JwtClaims(Map.of("jti", "jti-1", "sub", "42", "iat", 100));
    when(jwtVerifier.verify(token)).thenReturn(claims);
    when(jwtVerifier.toContext(claims))
        .thenReturn(new RagAuthContext(42L, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", "user:42"));
    when(authEventService.isJwtRevoked(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("redis down"));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(chain).doFilter(request, response);
  }

  @Test
  void adminOverrideAndOrgContextAreAvailableOnlyDuringChain() throws Exception {
    String token = "admin.jwt";
    JwtClaims claims = new JwtClaims(Map.of("jti", "jti-1", "user_id", 42, "iat", 100));
    RagAuthContext context =
        new RagAuthContext(42L, "ADMIN", Set.of(), Set.of(), Set.of("admin"), "USER", "user:42");
    when(jwtVerifier.verify(token)).thenReturn(claims);
    when(jwtVerifier.toContext(claims)).thenReturn(context);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    request.addHeader("X-Org-Id", " 99 ");
    request.addHeader("X-Admin-Override", "1");
    request.addHeader("X-Admin-Override-Reason", "support");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          assertThat(RagAuthContextHolder.get()).isEqualTo(context);
          assertThat(OrgContextHolder.get()).isEqualTo(99L);
          assertThat(AdminOverrideHolder.isActive()).isTrue();
          assertThat(AdminOverrideHolder.reason()).isEqualTo("support");
          assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
              .extracting(Object::toString)
              .containsExactly("ROLE_ADMIN");
        };

    filter.doFilter(request, response, chain);

    assertThat(RagAuthContextHolder.get()).isNull();
    assertThat(OrgContextHolder.get()).isNull();
    assertThat(AdminOverrideHolder.isActive()).isFalse();
    verify(adminAccessAuditService).recordKbBreakGlass(42L, "support");
  }

  @Test
  void nonAdminOverrideHeaderDoesNotActivateBreakGlass() throws Exception {
    String token = "user.jwt";
    JwtClaims claims = new JwtClaims(Map.of("jti", "jti-1", "user_id", 7, "iat", 100));
    RagAuthContext context =
        new RagAuthContext(7L, "USER", Set.of(), Set.of(), Set.of(), "USER", "user:7");
    when(jwtVerifier.verify(token)).thenReturn(claims);
    when(jwtVerifier.toContext(claims)).thenReturn(context);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    request.addHeader("X-Org-Id", "platform");
    request.addHeader("X-Admin-Override", "true");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          assertThat(OrgContextHolder.get()).isNull();
          assertThat(AdminOverrideHolder.isActive()).isFalse();
        };

    filter.doFilter(request, response, chain);

    verify(adminAccessAuditService, never()).recordKbBreakGlass(any(), any());
  }

  @Test
  void chainExceptionStillClearsContexts() throws Exception {
    String token = "boom.jwt";
    JwtClaims claims = new JwtClaims(Map.of("jti", "jti-1", "user_id", 7, "iat", 100));
    when(jwtVerifier.verify(token)).thenReturn(claims);
    when(jwtVerifier.toContext(claims))
        .thenReturn(new RagAuthContext(7L, "USER", Set.of(), Set.of(), Set.of(), "USER", "user:7"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    request.addHeader("X-Org-Id", "16");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    doThrow(new RuntimeException("downstream")).when(chain).doFilter(request, response);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, chain))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("downstream");

    assertThat(RagAuthContextHolder.get()).isNull();
    assertThat(OrgContextHolder.get()).isNull();
    assertThat(AdminOverrideHolder.isActive()).isFalse();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
