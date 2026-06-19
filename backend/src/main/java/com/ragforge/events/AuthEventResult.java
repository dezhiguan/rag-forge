package com.ragforge.events;

public record AuthEventResult(int status, String message, String eventId, int revokedJtiCount) {

  public static AuthEventResult accepted(String eventId, int revokedJtiCount) {
    return new AuthEventResult(200, "accepted", eventId, revokedJtiCount);
  }

  public static AuthEventResult duplicate(String eventId) {
    return new AuthEventResult(200, "duplicate", eventId, 0);
  }

  public static AuthEventResult invalidSignature() {
    return new AuthEventResult(401, "invalid signature", null, 0);
  }

  public static AuthEventResult badRequest(String message) {
    return new AuthEventResult(400, message, null, 0);
  }
}
