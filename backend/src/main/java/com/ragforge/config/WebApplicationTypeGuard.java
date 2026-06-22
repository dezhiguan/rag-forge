package com.ragforge.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class WebApplicationTypeGuard implements ApplicationRunner {

  private final Environment environment;

  public WebApplicationTypeGuard(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    String role = environment.getProperty("ragforge.role", "all");
    String webType = environment.getProperty("spring.main.web-application-type", "servlet");
    if (("worker".equalsIgnoreCase(role) || "judge".equalsIgnoreCase(role))
        && !"none".equalsIgnoreCase(webType)) {
      throw new IllegalStateException(
          "ragforge.role=" + role + " requires spring.main.web-application-type=none");
    }
  }
}
