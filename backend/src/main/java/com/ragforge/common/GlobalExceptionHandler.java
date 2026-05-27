package com.ragforge.common;

import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BizException.class)
  public Result<Void> handleBizException(BizException ex) {
    return Result.fail(ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
    return Result.fail(400, msg);
  }

  @ExceptionHandler(Exception.class)
  public Result<Void> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return Result.fail(500, "Internal Server Error");
  }
}
