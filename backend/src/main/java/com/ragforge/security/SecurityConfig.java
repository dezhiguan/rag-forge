package com.ragforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.Result;
import com.ragforge.web.TraceIds;
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
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(
                    (request, response, ex) -> writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                    .accessDeniedHandler(
                        (request, response, ex) -> writeJson(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/health", "/actuator/health", "/api/auth/**").permitAll()
                    .requestMatchers("/api/v1/.well-known/ragforge-admin-backend-jwks.json").permitAll()
                    .requestMatchers("/api/v1/events/**").permitAll()
                    .requestMatchers("/api/v1/search", "/api/v1/answer", "/api/v1/internal/**", "/mcp/**", "/sse", "/sse/**")
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

  private void writeJson(HttpServletResponse response, int status, String message) throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(TraceIds.HEADER_TRACE_ID, TraceIds.current());
    response.setHeader(TraceIds.HEADER_REQUEST_ID, TraceIds.currentRequestId());
    objectMapper.writeValue(response.getWriter(), Result.fail(status, message));
  }
}
