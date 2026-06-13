package com.ragforge.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceFilterTest {

  private final TraceFilter filter = new TraceFilter();

  @AfterEach
  void tearDown() {
    MDC.remove("traceId");
    MDC.remove("requestId");
    MDC.remove("sessionId");
  }

  @Test
  void echoesIncomingTraceIdWhenSkyWalkingUnavailable() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "upstream-trace-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals("upstream-trace-123", response.getHeader("X-Trace-Id"));
  }

  @Test
  void generatesRfTraceIdWhenMissing() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    String traceId = response.getHeader("X-Trace-Id");
    assertNotNull(traceId);
    assertTrue(traceId.startsWith("rf-"));
  }

  @Test
  void echoesRequestId() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "req-abc-456");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals("req-abc-456", response.getHeader("X-Request-Id"));
  }

  @Test
  void generatesRequestIdWhenMissing() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNotNull(response.getHeader("X-Request-Id"));
  }

  @Test
  void putsSessionIdInMdcDuringRequest() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "trace-in-request");
    request.addHeader("X-Request-Id", "request-in-request");
    request.addHeader("X-CareerMate-Session-Id", "sess-789");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (req, res) -> {
          assertEquals("trace-in-request", MDC.get("traceId"));
          assertEquals("request-in-request", MDC.get("requestId"));
          assertEquals("sess-789", MDC.get("sessionId"));
        });
  }

  @Test
  void mdcDoesNotLeakBetweenRequests() throws ServletException, IOException {
    MockHttpServletRequest first = new MockHttpServletRequest();
    first.addHeader("X-Trace-Id", "first-trace");
    first.addHeader("X-Request-Id", "first-request");
    first.addHeader("X-CareerMate-Session-Id", "first-session");
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();

    filter.doFilter(first, firstResponse, new MockFilterChain());

    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("requestId"));
    assertNull(MDC.get("sessionId"));

    MockHttpServletRequest second = new MockHttpServletRequest();
    second.addHeader("X-Trace-Id", "second-trace");
    second.addHeader("X-Request-Id", "second-request");
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();

    filter.doFilter(second, secondResponse, new MockFilterChain());

    assertEquals("second-trace", secondResponse.getHeader("X-Trace-Id"));
    assertEquals("second-request", secondResponse.getHeader("X-Request-Id"));
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("requestId"));
    assertNull(MDC.get("sessionId"));
  }

  @Test
  void restoresPreviousMdcValuesAfterRequest() throws ServletException, IOException {
    MDC.put("traceId", "previous-trace");
    MDC.put("requestId", "previous-request");
    MDC.put("sessionId", "previous-session");
    MDC.put("other", "untouched");

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "current-trace");
    request.addHeader("X-Request-Id", "current-request");
    request.addHeader("X-CareerMate-Session-Id", "current-session");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals("current-trace", response.getHeader("X-Trace-Id"));
    assertEquals("current-request", response.getHeader("X-Request-Id"));
    assertEquals("previous-trace", MDC.get("traceId"));
    assertEquals("previous-request", MDC.get("requestId"));
    assertEquals("previous-session", MDC.get("sessionId"));
    assertEquals("untouched", MDC.get("other"));
    MDC.remove("other");
  }

  @Test
  void rejectsIgnoredSkyWalkingTraceId() {
    assertTrue(TraceFilter.isUsableSkyWalkingTraceId("abc123"));
    assertFalse(TraceFilter.isUsableSkyWalkingTraceId("Ignored_Trace"));
    assertFalse(TraceFilter.isUsableSkyWalkingTraceId("N/A"));
    assertFalse(TraceFilter.isUsableSkyWalkingTraceId(""));
  }
}
