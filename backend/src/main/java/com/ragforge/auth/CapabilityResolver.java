package com.ragforge.auth;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 由 rag_role 推导前端能力清单（菜单显隐 / 按钮控权用）。
 * 真正的鉴权仍以后端 @PreAuthorize + KbAccessGuard 为准，此清单只驱动 UI。
 */
@Component
public class CapabilityResolver {

  private static final List<String> BASE_USER = List.of(
      "dashboard:read", "kb:read", "kb:own:write", "search:run", "answer:run", "profile:write");

  private static final List<String> ADMIN_EXTRA = List.of(
      "eval:write", "perf:read", "quality:read", "cost:read", "apikey:admin", "platform:admin");

  public List<String> capabilitiesFor(String ragRole) {
    String role = ragRole == null ? "USER" : ragRole.toUpperCase();
    return switch (role) {
      case "ADMIN" -> merge(BASE_USER, ADMIN_EXTRA);
      case "KB_EDITOR" -> merge(BASE_USER, List.of("eval:write"));
      default -> BASE_USER; // KB_VIEWER / USER / 未知
    };
  }

  public boolean isAdmin(String ragRole) {
    return "ADMIN".equalsIgnoreCase(ragRole);
  }

  private List<String> merge(List<String> a, List<String> b) {
    LinkedHashSet<String> set = new LinkedHashSet<>(a);
    set.addAll(b);
    return List.copyOf(set);
  }
}
