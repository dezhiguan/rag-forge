package com.ragforge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

@ExtendWith(MockitoExtension.class)
class ChunkImageResolverTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private ObjectStorage objectStorage;

  @InjectMocks private ChunkImageResolver resolver;

  @Test
  void presignedUrls_nullInput_returnsEmptyMap() {
    Map<Long, String> result = resolver.presignedUrls(null);
    assertThat(result).isEmpty();
    verify(jdbcTemplate, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
  }

  @Test
  void presignedUrls_emptyInput_returnsEmptyMap() {
    Map<Long, String> result = resolver.presignedUrls(List.of());
    assertThat(result).isEmpty();
    verify(jdbcTemplate, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
  }

  @Test
  void presignedUrls_jdbcException_returnsEmptyMapGracefully() {
    doThrow(new RuntimeException("column image_key does not exist"))
        .when(jdbcTemplate)
        .query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

    Map<Long, String> result = resolver.presignedUrls(List.of(1L, 2L, 3L));
    // Exception swallowed: empty map (graceful degradation)
    assertThat(result).isEmpty();
  }

  @Test
  void presignedUrls_queriesWithAllNonNullIds() {
    List<Long> ids = Arrays.asList(10L, 20L, null, 30L);
    // Should filter out nulls and query with 3 ids
    resolver.presignedUrls(ids);
    verify(jdbcTemplate).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
  }
}
