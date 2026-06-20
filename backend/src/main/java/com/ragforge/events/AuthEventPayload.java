package com.ragforge.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthEventPayload(
    @JsonAlias("event_id") String eventId,
    @JsonAlias("event_type") String type,
    String jti,
    @JsonAlias({"jtis", "revoked_jtis"}) List<String> jtis,
    @JsonAlias({"user_id", "uid"}) String userId,
    String sub,
    Long exp,
    Long iat,
    @JsonAlias({"changed_at", "revoked_at", "occurred_at"}) Object occurredAt,
    @JsonAlias("payload") Map<String, Object> data) {}
