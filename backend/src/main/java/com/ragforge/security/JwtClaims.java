package com.ragforge.security;

import java.util.List;
import java.util.Map;

public record JwtClaims(Map<String, Object> values) {

  public String string(String name) {
    Object value = values.get(name);
    return value == null ? null : String.valueOf(value);
  }

  public Long longValue(String name) {
    Object value = values.get(name);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    return Long.valueOf(String.valueOf(value));
  }

  public boolean audienceContains(String expected) {
    Object audience = values.get("aud");
    if (audience instanceof String text) {
      return expected.equals(text);
    }
    if (audience instanceof List<?> list) {
      return list.stream().anyMatch(item -> expected.equals(String.valueOf(item)));
    }
    return false;
  }

  public boolean expired(long nowEpochSeconds) {
    Long exp = longValue("exp");
    return exp == null || exp <= nowEpochSeconds;
  }
}
