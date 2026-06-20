package com.ragforge.common;

import java.util.Map;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

  private final int code;
  private final Map<String, Object> data;

  public BizException(String msg) {
    this(500, msg);
  }

  public BizException(int code, String msg) {
    this(code, msg, null);
  }

  public BizException(int code, String msg, Map<String, Object> data) {
    super(msg);
    this.code = code;
    this.data = data;
  }
}
