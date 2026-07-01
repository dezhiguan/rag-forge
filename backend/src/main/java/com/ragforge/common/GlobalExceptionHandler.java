package com.ragforge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @ExceptionHandler(BizException.class)
  public void handleBizException(BizException ex, HttpServletResponse response) throws IOException {
    // ex.getMessage() 是机器码 → errorCode；msg 翻成中文用户提示；错误体不带 traceId（保留在响应头供排查）。
    String errorCode = ex.getMessage();
    Result<Map<String, Object>> body =
        new Result<>(
            ex.getCode(), ErrorMessages.toChinese(errorCode), errorCode, ex.getData(), null);
    response.setStatus(ex.getCode());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader(TraceIds.HEADER_TRACE_ID, TraceIds.current());
    OBJECT_MAPPER.writeValue(response.getWriter(), body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException ex) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(Result.fail(400, msg));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
    log.warn(
        "Multipart upload exceeded servlet limit: maxBytes={}, exceeded={}",
        RelayUploadLimits.RELAY_UPLOAD_LIMIT_BYTES,
        ex.getMaxUploadSize());
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(
            Map.of(
                "error",
                "FILE_TOO_LARGE_FOR_RELAY",
                "presignUrl",
                RelayUploadLimits.PRESIGN_URL,
                "limitMb",
                RelayUploadLimits.RELAY_UPLOAD_LIMIT_MB));
  }

  /** 上传接口收到非 multipart 请求（如误用 JSON）：返回 415 而非 500。 */
  @ExceptionHandler({
    org.springframework.web.multipart.MultipartException.class,
    org.springframework.web.HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<Result<Void>> handleUnsupportedMediaType(Exception ex) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(Result.fail(415, "该接口需使用 multipart/form-data 上传文件"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Result<Void>> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    if (request != null && "/api/v1/documents/text".equals(request.getRequestURI())) {
      return ResponseEntity.status(404).body(Result.fail(404, "Not Found"));
    }
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(Result.fail(405, "Method Not Allowed"));
  }

  @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
  public ResponseEntity<Result<Void>> handleAccessDenied(Exception ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(Result.error(403, "FORBIDDEN", "无权访问，请联系管理员"));
  }

  // 前端偶发把 NaN / 空串作为 path/query 数字参数传过来（例如 /kb/NaN/documents），
  // 默认会落到 Exception → 500 + 噪声堆栈。改为 400，便于前端识别并刷新本地状态。
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    String name = ex.getName();
    Object value = ex.getValue();
    log.warn("Invalid request param: name={}, value={}", name, value);
    String msg = String.format("INVALID_PARAM: %s=%s", name, value);
    return ResponseEntity.badRequest().body(Result.fail(400, msg));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
    return ResponseEntity.badRequest()
        .body(Result.fail(400, "MISSING_PARAM: " + ex.getParameterName()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError()
        .body(Result.error(500, "INTERNAL_ERROR", "服务暂时异常，请稍后重试"));
  }
}
