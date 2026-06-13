package com.ragforge.common;

import com.ragforge.web.TraceIds;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

  private int code;
  private String msg;
  private T data;
  private String traceId;

  public static <T> Result<T> ok(T data) {
    return new Result<>(200, "success", data, TraceIds.current());
  }

  public static <T> Result<T> ok() {
    return ok(null);
  }

  public static <T> Result<T> fail(String msg) {
    return fail(500, msg);
  }

  public static <T> Result<T> fail(int code, String msg) {
    return new Result<>(code, msg, null, TraceIds.current());
  }
}
