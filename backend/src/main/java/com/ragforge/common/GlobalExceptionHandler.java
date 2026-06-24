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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @ExceptionHandler(BizException.class)
  public void handleBizException(BizException ex, HttpServletResponse response) throws IOException {
    Result<Map<String, Object>> body =
        new Result<>(ex.getCode(), ex.getMessage(), ex.getData(), TraceIds.current());
    response.setStatus(ex.getCode());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
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
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(403, "Forbidden"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError().body(Result.fail(500, "Internal Server Error"));
  }
}
