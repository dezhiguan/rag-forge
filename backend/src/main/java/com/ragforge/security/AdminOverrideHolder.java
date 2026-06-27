package com.ragforge.security;

/**
 * 平台 ADMIN 的"破玻璃"(break-glass)提权标记，请求级 ThreadLocal。
 *
 * <p>默认情况下 ADMIN 对业务知识库与普通用户同口径（自有 + public + acl），不读他人私库。
 * 仅当本次请求显式携带 {@code X-Admin-Override} 头时，由过滤器置入本标记并写审计，
 * {@link KbAccessGuard} 据此临时放行到"全部非 SYSTEM 库"。
 */
public final class AdminOverrideHolder {

  private static final ThreadLocal<String> REASON = new ThreadLocal<>();

  private AdminOverrideHolder() {}

  public static void activate(String reason) {
    REASON.set(reason == null ? "" : reason);
  }

  public static boolean isActive() {
    return REASON.get() != null;
  }

  public static String reason() {
    return REASON.get();
  }

  public static void clear() {
    REASON.remove();
  }
}
