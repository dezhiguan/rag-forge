package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.events.AuthEventService;
import java.util.Set;
import java.util.Map;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  private JwtVerifier jwtVerifier;
  private AuthEventService authEventService;
  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    RagAuthContextHolder.clear();
    SecurityContextHolder.clearContext();
    jwtVerifier = mock(JwtVerifier.class);
    authEventService = mock(AuthEventService.class);
    filter =
        new JwtAuthenticationFilter(
            jwtVerifier, new ObjectMapper(), authEventService, mock(AdminAccessAuditService.class));
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
}
