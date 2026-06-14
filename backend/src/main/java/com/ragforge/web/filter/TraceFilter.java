package com.ragforge.web.filter;

import com.ragforge.web.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

  private static final String TRACE_ID_KEY = "traceId";
  private static final String REQUEST_ID_KEY = "requestId";
  private static final String SESSION_ID_KEY = "sessionId";
  private static final String SERVICE_KEY = "service";

  private final String serviceName;

  public TraceFilter(@Value("${spring.application.name:ragforge-backend}") String serviceName) {
    this.serviceName = serviceName;
  }

  private static final String HEADER_TRACE_ID = TraceIds.HEADER_TRACE_ID;
  private static final String HEADER_REQUEST_ID = TraceIds.HEADER_REQUEST_ID;
  private static final String HEADER_SESSION_ID = "X-CareerMate-Session-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    MdcSnapshot mdcSnapshot = MdcSnapshot.capture();
    String requestId = resolveRequestId(request);
    String incomingTraceId = incomingTraceId(request);

    MDC.put(REQUEST_ID_KEY, requestId);
    MDC.put(SERVICE_KEY, serviceName);
    putSessionIdIfPresent(request);
    MDC.remove(TRACE_ID_KEY);
    syncTraceMdc(incomingTraceId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      syncTraceMdc(incomingTraceId);
      String finalTraceId = MDC.get(TRACE_ID_KEY);
      response.setHeader(HEADER_TRACE_ID, finalTraceId);
      response.setHeader(HEADER_REQUEST_ID, requestId);
      mdcSnapshot.restore();
    }
  }

  private void syncTraceMdc(String incomingTraceId) {
    MDC.put(TRACE_ID_KEY, TraceIds.resolve(incomingTraceId));
  }

  private void putSessionIdIfPresent(HttpServletRequest request) {
    String sessionId = request.getHeader(HEADER_SESSION_ID);
    if (StringUtils.hasText(sessionId)) {
      MDC.put(SESSION_ID_KEY, sessionId.trim());
    }
  }

  private String resolveRequestId(HttpServletRequest request) {
    String incoming = request.getHeader(HEADER_REQUEST_ID);
    if (StringUtils.hasText(incoming)) {
      return incoming.trim();
    }
    return UUID.randomUUID().toString();
  }

  private String incomingTraceId(HttpServletRequest request) {
    String incoming = request.getHeader(HEADER_TRACE_ID);
    if (StringUtils.hasText(incoming)) {
      return incoming.trim();
    }
    return null;
  }

  private record MdcSnapshot(String traceId, String requestId, String sessionId) {

    private static MdcSnapshot capture() {
      return new MdcSnapshot(
          MDC.get(TRACE_ID_KEY), MDC.get(REQUEST_ID_KEY), MDC.get(SESSION_ID_KEY));
    }

    private void restore() {
      restoreKey(TRACE_ID_KEY, traceId);
      restoreKey(REQUEST_ID_KEY, requestId);
      restoreKey(SESSION_ID_KEY, sessionId);
    }

    private static void restoreKey(String key, String value) {
      if (value == null) {
        MDC.remove(key);
      } else {
        MDC.put(key, value);
      }
    }
  }
}
