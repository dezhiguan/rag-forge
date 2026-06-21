package com.ragforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PgvectorVersionGuardTest {

  @Test
  void compareVersion_handlesPgvectorVersions() {
    assertThat(PgvectorVersionGuard.compareVersion("0.8.1", "0.7")).isGreaterThan(0);
    assertThat(PgvectorVersionGuard.compareVersion("0.7.0", "0.7")).isZero();
    assertThat(PgvectorVersionGuard.compareVersion("0.6.2", "0.7")).isLessThan(0);
  }
}
