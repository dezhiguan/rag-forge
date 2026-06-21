package com.ragforge.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PgvectorVersionGuard {

  private static final String REQUIRED_VERSION = "0.7";

  private final JdbcTemplate jdbcTemplate;

  @PostConstruct
  public void verifyPgvector() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname='vector'", String.class);
    if (version == null || compareVersion(version, REQUIRED_VERSION) < 0) {
      throw new IllegalStateException("pgvector >= 0.7 required for 2560 dim vector");
    }

    Boolean hasVlVector =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM information_schema.columns
              WHERE table_name = 'document_chunks'
                AND column_name = 'vl_vector'
            )
            """,
            Boolean.class);
    if (!Boolean.TRUE.equals(hasVlVector)) {
      throw new IllegalStateException(
          "document_chunks.vl_vector missing, 请先人工执行 db/manual/V27__vl_unified_vector.sql");
    }
    log.info("pgvector version check passed: extversion={}", version);
  }

  static int compareVersion(String left, String right) {
    int[] a = parse(left);
    int[] b = parse(right);
    int max = Math.max(a.length, b.length);
    for (int i = 0; i < max; i++) {
      int av = i < a.length ? a[i] : 0;
      int bv = i < b.length ? b[i] : 0;
      if (av != bv) {
        return Integer.compare(av, bv);
      }
    }
    return 0;
  }

  private static int[] parse(String version) {
    if (version == null || version.isBlank()) {
      return new int[] {0};
    }
    return Arrays.stream(version.split("[^0-9]+"))
        .filter(part -> !part.isBlank())
        .mapToInt(Integer::parseInt)
        .toArray();
  }
}
