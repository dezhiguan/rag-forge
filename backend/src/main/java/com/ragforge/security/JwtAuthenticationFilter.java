package com.ragforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.ErrorMessages;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String ADMIN_OVERRIDE_HEADER = "X-Admin-Override";
  private static final String ADMIN_OVERRIDE_REASON_HEADER = "X-Admin-Override-Reason";

  private final JwtVerifier jwtVerifier;
  private final ObjectMapper objectMapper;
  private final AuthEventService authEventService;
  private final AdminAccessAuditService adminAccessAuditService;
  private final com.ragforge.mapper.OrgMemberMapper orgMemberMapper;

  /**
   * /api/auth/** 是公开的认证代理端点（登录/刷新/登出/找回密码/凭据管理），它们要么用 rf_refresh
   * cookie、要么把 Authorization 头原样转发给网关自行校验，从不依赖本过滤器写入的 RagAuthContext。
   *
   * <p>本过滤器对任意带 Bearer 的请求都会校验，失败即 401 并中断链路。前端 authClient 会给
   * /api/auth/refresh 也带上当前 access token；笔记本合盖休眠时主动续期定时器被冻结、access token 到期，
   * 唤醒后这次刷新携带的正是**已过期**的 Bearer——若不跳过，refresh（其存在意义就是在 access token
   * 过期时换新令牌）会被本过滤器以「登录状态已失效」401 拦死，导致「记住我 30 天」内合盖必被踢登录。
   * 故对 /api/auth/** 跳过本过滤器：这些端点的鉴权由其自身/网关负责。
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    if (path == null || path.isEmpty()) {
      path = request.getRequestURI();
    }
    return path != null && path.startsWith("/api/auth/");
  }

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
          // 会话撤销（含被移出组织）统一走 isJwtRevoked：auth-gateway 撤销会话时发 session.revoked 事件，
          // 本应用据此在 Redis 记录该用户令牌撤销点，拦截撤销点之前签发的旧 token。不再跨库查 auth_users。
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
        maybeActivateAdminOverride(context, request);
        OrgContextHolder.set(resolveOrgContext(context, request));
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
      AdminOverrideHolder.clear();
      OrgContextHolder.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private static Long parseOrgId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return null; // 非数字（如 'platform'）忽略
    }
  }

  /**
   * 解析并校验组织上下文。X-Org-Id 仅在其为当前用户所属组织时才生效；否则忽略（置 null，按无组织上下文处理），
   * 避免用户伪造 X-Org-Id 越权读取他组织的数据（成本看板 / Dashboard 概览等均按此上下文过滤）。
   *
   * <p>破玻璃（全平台视图，AdminOverrideHolder 已激活）豁免成员校验：超管显式提权后按全平台口径取数。
   */
  private Long resolveOrgContext(RagAuthContext context, HttpServletRequest request) {
    Long orgId = parseOrgId(request.getHeader("X-Org-Id"));
    if (orgId == null) {
      return null;
    }
    if (AdminOverrideHolder.isActive()) {
      return orgId; // 破玻璃：平台视图不做成员校验
    }
    if (context == null || context.userId() == null) {
      return null;
    }
    if (!orgMemberMapper.isMember(orgId, context.userId())) {
      log.warn(
          "Ignored X-Org-Id={} for non-member userId={} (org context reset to null)",
          orgId,
          context.userId());
      return null;
    }
    return orgId;
  }

  /** 仅 ADMIN 显式携带 X-Admin-Override 头时才提权，并写审计；其它情况一律按默认口径。 */
  private void maybeActivateAdminOverride(RagAuthContext context, HttpServletRequest request) {
    if (context == null || !context.isAdmin()) {
      return;
    }
    String flag = request.getHeader(ADMIN_OVERRIDE_HEADER);
    if (!StringUtils.hasText(flag) || !("true".equalsIgnoreCase(flag) || "1".equals(flag))) {
      return;
    }
    // 理由头由前端 encodeURIComponent 编码(HTTP 头仅允许 ISO-8859-1,中文需编码传输),此处解码还原。
    String reason = decodeOverrideReason(request.getHeader(ADMIN_OVERRIDE_REASON_HEADER));
    AdminOverrideHolder.activate(reason);
    adminAccessAuditService.recordKbBreakGlass(context.userId(), reason);
  }

  private static String decodeOverrideReason(String raw) {
    if (raw == null || raw.isEmpty()) {
      return raw;
    }
    try {
      return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
    } catch (RuntimeException ex) {
      return raw; // 非编码值(如历史/兜底 platform-view)原样返回
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
    // 令牌失效/过期/被吊销 → 返回友好中文 + 机器码，与安全层入口点口径一致（前端按 errorCode 本地化）。
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(TraceIds.HEADER_TRACE_ID, TraceIds.current());
    response.setHeader(TraceIds.HEADER_REQUEST_ID, TraceIds.currentRequestId());
    objectMapper.writeValue(
        response.getWriter(),
        Result.error(
            HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", ErrorMessages.toChinese("UNAUTHORIZED")));
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
