package com.ragforge.security;

/**
 * 当前组织上下文（X-Org-Id），请求级 ThreadLocal。
 *
 * <p>前端切换组织时带 {@code X-Org-Id}，由 {@link JwtAuthenticationFilter} 置入，用于把知识库/指标等
 * 列表按"当前所在组织"过滤（个人组织只看个人库、团队组织只看该组织库）。为空表示未指定上下文。
 */
public final class OrgContextHolder {

  private static final ThreadLocal<Long> ORG_ID = new ThreadLocal<>();

  private OrgContextHolder() {}

  public static void set(Long orgId) {
    ORG_ID.set(orgId);
  }

  public static Long get() {
    return ORG_ID.get();
  }

  public static void clear() {
    ORG_ID.remove();
  }
}
