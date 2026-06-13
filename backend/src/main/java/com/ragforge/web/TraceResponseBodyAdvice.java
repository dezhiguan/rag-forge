package com.ragforge.web;

import com.ragforge.common.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class TraceResponseBodyAdvice implements ResponseBodyAdvice<Result<?>> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return Result.class.isAssignableFrom(returnType.getParameterType());
  }

  @Override
  public Result<?> beforeBodyWrite(
      Result<?> body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body == null) {
      return null;
    }
    String incomingTraceId = request.getHeaders().getFirst(TraceIds.HEADER_TRACE_ID);
    String traceId = TraceIds.resolve(incomingTraceId);
    String requestId = TraceIds.currentRequestId();
    body.setTraceId(traceId);
    if (response instanceof ServletServerHttpResponse servletResponse) {
      servletResponse.getServletResponse().setHeader(TraceIds.HEADER_TRACE_ID, traceId);
      servletResponse.getServletResponse().setHeader(TraceIds.HEADER_REQUEST_ID, requestId);
    }
    return body;
  }
}
