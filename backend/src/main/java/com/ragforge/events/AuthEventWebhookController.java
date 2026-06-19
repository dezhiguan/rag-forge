package com.ragforge.events;

import com.ragforge.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class AuthEventWebhookController {

  private final AuthEventService authEventService;

  @PostMapping("/session-revoked")
  public ResponseEntity<Result<AuthEventResult>> sessionRevoked(
      @RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
    return handle("session.revoked", rawBody, headers);
  }

  @PostMapping("/password-changed")
  public ResponseEntity<Result<AuthEventResult>> passwordChanged(
      @RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
    return handle("user.password.changed", rawBody, headers);
  }

  private ResponseEntity<Result<AuthEventResult>> handle(String expectedType, String rawBody, HttpHeaders headers) {
    try {
      return toResponse(authEventService.handle(expectedType, rawBody, headers));
    } catch (IllegalArgumentException ex) {
      return toResponse(AuthEventResult.badRequest("invalid event payload"));
    }
  }

  private ResponseEntity<Result<AuthEventResult>> toResponse(AuthEventResult eventResult) {
    if (eventResult.status() == 200) {
      return ResponseEntity.ok(Result.ok(eventResult));
    }
    return ResponseEntity.status(eventResult.status()).body(Result.fail(eventResult.status(), eventResult.message()));
  }
}
