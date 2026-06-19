package com.ragforge.security;

public class JwtInvalidException extends RuntimeException {

  public JwtInvalidException(String message) {
    super(message);
  }

  public JwtInvalidException(String message, Throwable cause) {
    super(message, cause);
  }
}
