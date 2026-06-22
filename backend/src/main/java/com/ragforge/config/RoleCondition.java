package com.ragforge.config;

import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

abstract class RoleCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return matchesRole(role(context));
  }

  protected abstract boolean matchesRole(String role);

  static String role(ConditionContext context) {
    String raw = context.getEnvironment().getProperty("ragforge.role", "all");
    return raw == null || raw.isBlank() ? "all" : raw.trim().toLowerCase(Locale.ROOT);
  }
}
