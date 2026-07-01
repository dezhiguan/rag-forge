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
  /** 用户可读消息：成功为 "success"，失败为中文提示。 */
  private String msg;
  /** 机器可读错误码（如 ORG_SLUG_INVALID）；成功为 null，供前端分支判断。 */
  private String errorCode;
  private T data;
  private String traceId;

  public static <T> Result<T> ok(T data) {
    return new Result<>(200, "success", null, data, TraceIds.current());
  }

  public static <T> Result<T> ok() {
    return ok(null);
  }

  /** 失败响应（未分类）：错误体不再携带 traceId（保留在 X-Trace-Id 响应头），errorCode 为 null。 */
  public static <T> Result<T> fail(String msg) {
    return fail(500, msg);
  }

  public static <T> Result<T> fail(int code, String msg) {
    return new Result<>(code, msg, null, null, null);
  }

  /** 失败响应（带机器码 + 中文 msg），错误体不携带 traceId。 */
  public static <T> Result<T> error(int code, String errorCode, String msg) {
    return new Result<>(code, msg, errorCode, null, null);
  }
}
