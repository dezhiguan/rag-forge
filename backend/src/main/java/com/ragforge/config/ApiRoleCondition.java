package com.ragforge.config;

public class ApiRoleCondition extends RoleCondition {

  @Override
  protected boolean matchesRole(String role) {
    return "api".equals(role) || "all".equals(role);
  }
}
