package com.ragforge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class BaseIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg15")
          .withDatabaseName("ragforge_it")
          .withUsername("ragforge")
          .withPassword("ragforge");

  @DynamicPropertySource
  static void postgresqlProperties(DynamicPropertyRegistry registry) {
    String url = firstNonBlank(
        System.getProperty("RAGFORGE_TEST_PG_URL"),
        System.getenv("RAGFORGE_TEST_PG_URL"),
        System.getProperty("SPRING_DATASOURCE_URL"),
        System.getenv("SPRING_DATASOURCE_URL"));

    if (url != null) {
      String username =
          firstNonBlank(
              System.getProperty("RAGFORGE_TEST_PG_USERNAME"),
              System.getenv("RAGFORGE_TEST_PG_USERNAME"),
              System.getProperty("SPRING_DATASOURCE_USERNAME"),
              System.getenv("SPRING_DATASOURCE_USERNAME"));
      String password =
          firstNonBlank(
              System.getProperty("RAGFORGE_TEST_PG_PASSWORD"),
              System.getenv("RAGFORGE_TEST_PG_PASSWORD"),
              System.getProperty("SPRING_DATASOURCE_PASSWORD"),
              System.getenv("SPRING_DATASOURCE_PASSWORD"));

      String driverClassName =
          firstNonBlank(
              System.getProperty("RAGFORGE_TEST_PG_DRIVER"),
              System.getenv("RAGFORGE_TEST_PG_DRIVER"),
              "org.postgresql.Driver");

      registry.add("spring.datasource.url", () -> url);
      registry.add("spring.datasource.username", () -> username);
      registry.add("spring.datasource.password", () -> password);
      registry.add("spring.datasource.driver-class-name", () -> driverClassName);
    } else {
      if (!POSTGRES.isRunning()) {
        POSTGRES.start();
      }
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration");
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
