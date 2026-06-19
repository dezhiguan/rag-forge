package com.ragforge.security;

public final class RagAuthContextHolder {

  private static final ThreadLocal<RagAuthContext> HOLDER = new ThreadLocal<>();

  private RagAuthContextHolder() {}

  public static void set(RagAuthContext context) {
    HOLDER.set(context);
  }

  public static RagAuthContext get() {
    return HOLDER.get();
  }

  public static void clear() {
    HOLDER.remove();
  }
}
