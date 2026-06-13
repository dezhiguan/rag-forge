package com.ragforge.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements Filter {

  private static final String TRACE_ID_KEY = "traceId";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String traceId = extractOrGenerate(request);
    MDC.put(TRACE_ID_KEY, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }

  private String extractOrGenerate(ServletRequest request) {
    // 如果上游传了 X-Trace-Id，直接复用
    if (request instanceof HttpServletRequest httpRequest) {
      String incoming = httpRequest.getHeader("X-Trace-Id");
      if (incoming != null && !incoming.isBlank()) {
        return incoming;
      }
    }
    return "rf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
