package com.ragforge.events;

public record AuthJwtToken(String jti, String userKey, Long issuedAtEpochSeconds) {}
