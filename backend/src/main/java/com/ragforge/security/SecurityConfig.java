package com.ragforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.ErrorMessages;
import com.ragforge.common.Result;
import com.ragforge.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final ObjectMapper objectMapper;

  @Bean
  @Order(1)
  public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/actuator/**")
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(httpBasic -> httpBasic.realmName("ragforge-metrics"))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/info")
                    .hasRole("METRICS_READER")
                    .anyRequest().authenticated())
        .build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(
                    (request, response, ex) ->
                        writeJson(
                            response, HttpServletResponse.SC_UNAUTHORIZED, resolveUnauthenticatedCode(request)))
                    .accessDeniedHandler(
                        (request, response, ex) -> writeJson(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/health", "/actuator/health", "/api/auth/**").permitAll()
                    .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/info")
                    .hasRole("METRICS_READER")
                    .requestMatchers("/api/v1/.well-known/ragforge-admin-backend-jwks.json").permitAll()
                    .requestMatchers("/api/v1/events/**").permitAll()
                    // SSE 未读数推送：EventSource 不能带 Authorization header，故放行到 Controller，
                    // 由 NotificationSseService 用 query-param 的 token 自行验签鉴权。
                    .requestMatchers("/api/v1/notifications/stream").permitAll()
                    .requestMatchers("/api/v1/search", "/api/v1/answer", "/api/v1/documents", "/api/v1/internal/**", "/mcp", "/mcp/**")
                    .access(
                        (authentication, context) ->
                            new AuthorizationDecision(
                                (authentication.get() != null
                                        && authentication.get().isAuthenticated()
                                        && !(authentication.get() instanceof AnonymousAuthenticationToken))
                                    || context.getRequest().getHeader("X-API-Key") != null))
                    .anyRequest().authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /**
   * 未认证时的机器码分级：服务型接口缺 X-API-Key → 引导携带密钥；携带了 Bearer 令牌却仍到达入口点
   * （少见，失效/过期令牌通常已在 JwtAuthenticationFilter 短路）→ 登录态失效;完全未携带任何凭证 → 引导先登录。
   */
  private static String resolveUnauthenticatedCode(HttpServletRequest request) {
    if (isApiKeyServicePath(request) && request.getHeader("X-API-Key") == null) {
      return "API_KEY_MISSING";
    }
    return hasBearerToken(request) ? "UNAUTHORIZED" : "LOGIN_REQUIRED";
  }

  private static boolean hasBearerToken(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    return authorization != null && authorization.startsWith("Bearer ");
  }

  /** 是否为 API Key 鉴权的服务型接口（与 authorizeHttpRequests 中的放行集合保持一致）。 */
  private static boolean isApiKeyServicePath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return false;
    }
    return uri.startsWith("/api/v1/search")
        || uri.startsWith("/api/v1/answer")
        || uri.startsWith("/api/v1/documents")
        || uri.startsWith("/api/v1/internal/")
        || uri.equals("/mcp")
        || uri.startsWith("/mcp/");
  }

  /** 安全层拒绝响应：机器码放 errorCode，msg 翻成友好中文（外部直连亦可读）。 */
  private void writeJson(HttpServletResponse response, int status, String errorCode)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(TraceIds.HEADER_TRACE_ID, TraceIds.current());
    response.setHeader(TraceIds.HEADER_REQUEST_ID, TraceIds.currentRequestId());
    objectMapper.writeValue(
        response.getWriter(), Result.error(status, errorCode, ErrorMessages.toChinese(errorCode)));
  }
}
