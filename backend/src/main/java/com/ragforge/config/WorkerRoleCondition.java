package com.ragforge.config;

public class WorkerRoleCondition extends RoleCondition {

  @Override
  protected boolean matchesRole(String role) {
    return "worker".equals(role) || "all".equals(role);
  }
}
