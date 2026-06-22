package com.ragforge.config;

public class JudgeRoleCondition extends RoleCondition {

  @Override
  protected boolean matchesRole(String role) {
    return "judge".equals(role) || "all".equals(role);
  }
}
