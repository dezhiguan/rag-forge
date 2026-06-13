package com.ragforge.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ResultTest {

  @AfterEach
  void tearDown() {
    MDC.remove("traceId");
    MDC.remove("requestId");
  }

  @Test
  void okUsesMdcTraceIdWhenPresent() {
    MDC.put("traceId", "mdc-trace-123");

    Result<String> result = Result.ok("data");

    assertEquals("mdc-trace-123", result.getTraceId());
  }

  @Test
  void okGeneratesTraceIdWhenMdcMissing() {
    Result<String> result = Result.ok();

    assertNotNull(result.getTraceId());
    assertTrue(
        result.getTraceId().startsWith("rf-") || result.getTraceId().equals(MDC.get("requestId")));
  }

  @Test
  void failIncludesTraceId() {
    MDC.put("traceId", "error-trace-456");

    Result<Void> result = Result.fail(401, "Unauthorized");

    assertEquals(401, result.getCode());
    assertEquals("Unauthorized", result.getMsg());
    assertEquals("error-trace-456", result.getTraceId());
  }

  @Test
  void failGeneratesTraceIdWhenMdcMissing() {
    Result<Void> result = Result.fail("error");

    assertNotNull(result.getTraceId());
    assertTrue(result.getTraceId().startsWith("rf-"));
  }
}
