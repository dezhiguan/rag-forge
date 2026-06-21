package com.ragforge.document.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.HttpStatus;

@Data
@AllArgsConstructor
public class UploadRelayResult {

  private HttpStatus status;
  private Map<String, Object> body;

  public static UploadRelayResult ok(Map<String, Object> body) {
    return new UploadRelayResult(HttpStatus.OK, body);
  }

  public static UploadRelayResult payloadTooLarge(Map<String, Object> body) {
    return new UploadRelayResult(HttpStatus.PAYLOAD_TOO_LARGE, body);
  }
}
