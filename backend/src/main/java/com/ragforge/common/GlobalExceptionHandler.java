package com.ragforge.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BizException.class)
  public ResponseEntity<Result<Void>> handleBizException(BizException ex) {
    return ResponseEntity.status(ex.getCode()).body(Result.fail(ex.getCode(), ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException ex) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(Result.fail(400, msg));
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

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError().body(Result.fail(500, "Internal Server Error"));
  }
}
