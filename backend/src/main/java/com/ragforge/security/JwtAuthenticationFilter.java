package com.ragforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.Result;
import com.ragforge.events.AuthEventService;
import com.ragforge.events.AuthJwtToken;
import com.ragforge.web.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtVerifier jwtVerifier;
  private final ObjectMapper objectMapper;
  private final AuthEventService authEventService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String token = bearerToken(request);
      if (StringUtils.hasText(token)) {
        JwtClaims claims;
        RagAuthContext context;
        try {
          claims = jwtVerifier.verify(token);
          if (isJwtRevoked(claims)) {
            writeUnauthorized(response);
            return;
          }
          context = jwtVerifier.toContext(claims);
        } catch (IllegalArgumentException | IllegalStateException | JwtInvalidException ex) {
          writeUnauthorized(response);
          return;
        } catch (JwtInfrastructureException ex) {
          log.error("JWT infrastructure failure", ex);
          writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable");
          return;
        } catch (RuntimeException ex) {
          log.error("Unexpected JWT authentication failure", ex);
          writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
          return;
        }
        RagAuthContextHolder.set(context);
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                context,
                token,
                List.of(new SimpleGrantedAuthority("ROLE_" + context.ragRole())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
      filterChain.doFilter(request, response);
    } finally {
      RagAuthContextHolder.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private boolean isJwtRevoked(JwtClaims claims) {
    try {
      return authEventService.isJwtRevoked(
          new AuthJwtToken(claims.string("jti"), claims.userKey(), claims.longValue("iat")));
    } catch (RuntimeException ex) {
      log.warn("JWT revocation check unavailable, continuing with signature-verified token: {}", ex.getMessage());
      return false;
    }
  }

  private void writeUnauthorized(HttpServletResponse response) throws IOException {
    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
  }

  private void writeError(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(TraceIds.HEADER_TRACE_ID, TraceIds.current());
    response.setHeader(TraceIds.HEADER_REQUEST_ID, TraceIds.currentRequestId());
    objectMapper.writeValue(response.getWriter(), Result.fail(status, message));
  }

  private String bearerToken(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
      return null;
    }
    return authorization.substring("Bearer ".length()).trim();
  }
}
