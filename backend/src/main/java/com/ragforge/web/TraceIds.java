package com.ragforge.web;

import java.util.UUID;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class TraceIds {

  public static final String HEADER_TRACE_ID = "X-Trace-Id";
  public static final String HEADER_REQUEST_ID = "X-Request-Id";

  private static final String MDC_TRACE_ID = "traceId";
  private static final String MDC_REQUEST_ID = "requestId";
  private static final String SKYWALKING_IGNORED_TRACE = "Ignored_Trace";

  private TraceIds() {}

  /** Resolve trace id for the current request (SkyWalking → MDC → incoming header → requestId → rf-). */
  public static String current() {
    return resolve(null);
  }

  public static String resolve(String incomingTraceId) {
    String skyWalkingTraceId = TraceContext.traceId();
    if (isUsableTraceId(skyWalkingTraceId)) {
      return skyWalkingTraceId;
    }
    String mdcTraceId = MDC.get(MDC_TRACE_ID);
    if (isUsableTraceId(mdcTraceId)) {
      return mdcTraceId;
    }
    if (isUsableTraceId(incomingTraceId)) {
      return incomingTraceId.trim();
    }
    String mdcRequestId = MDC.get(MDC_REQUEST_ID);
    if (isUsableTraceId(mdcRequestId)) {
      return mdcRequestId;
    }
    return generateLocalTraceId();
  }

  public static String currentRequestId() {
    String requestId = MDC.get(MDC_REQUEST_ID);
    if (isUsableTraceId(requestId)) {
      return requestId;
    }
    return UUID.randomUUID().toString();
  }

  public static boolean isUsableTraceId(String traceId) {
    return StringUtils.hasText(traceId)
        && !SKYWALKING_IGNORED_TRACE.equals(traceId)
        && !"N/A".equalsIgnoreCase(traceId);
  }

  public static String generateLocalTraceId() {
    return "rf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
