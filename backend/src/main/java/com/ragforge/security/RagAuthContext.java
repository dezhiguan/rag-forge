package com.ragforge.security;

import java.util.Set;

public record RagAuthContext(
    Long userId,
    String tenantId,
    String ragRole,
    Set<Long> readableKbIds,
    Set<Long> writableKbIds,
    Set<String> scopes,
    String principalType,
    String principalId) {

  public boolean isAdmin() {
    return "ADMIN".equals(ragRole);
  }

  public boolean hasScope(String scope) {
    return scopes != null && scopes.contains(scope);
  }
}
