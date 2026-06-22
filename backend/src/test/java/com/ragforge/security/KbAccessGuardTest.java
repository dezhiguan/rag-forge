package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KbAclMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.KnowledgeBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KbAccessGuardTest {

  @Mock private KbAclMapper kbAclMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  private SimpleMeterRegistry meterRegistry;
  private KbAccessGuard guard;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    guard = new KbAccessGuard(kbAclMapper, knowledgeBaseMapper, documentMapper, new RagforgeMetrics(meterRegistry));
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
  }

  @Test
  void kbViewer_canReadOnlyReadableKbIds() {
    RagAuthContextHolder.set(new RagAuthContext(7L, null, "KB_VIEWER", Set.of(1L), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(1L)).isTrue();
    assertThat(guard.canRead(2L)).isFalse();
  }

  @Test
  void admin_canReadAnyNonSystemKb() {
    KnowledgeBase normal = new KnowledgeBase();
    normal.setId(10L);
    normal.setKbType("USER");
    KnowledgeBase system = new KnowledgeBase();
    system.setId(11L);
    system.setKbType("SYSTEM");
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(normal);
    when(knowledgeBaseMapper.selectById(11L)).thenReturn(system);
    RagAuthContextHolder.set(new RagAuthContext(1L, null, "ADMIN", Set.of(), Set.of(), Set.of(), "user", "1"));

    assertThat(guard.canRead(10L)).isTrue();
    assertThat(guard.canRead(11L)).isFalse();
  }

  @Test
  void filterReadable_recordsAuditMetricForDeniedDiff() {
    RagAuthContextHolder.set(new RagAuthContext(7L, null, "KB_VIEWER", Set.of(1L), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.filterReadable(List.of(1L, 2L))).containsExactly(1L);
    assertThat(meterRegistry.counter("ragforge.authz.kb_access_denied", "operation", "filter_readable").count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.counter("ragforge.kb_access_denied", "operation", "filter_readable").count())
        .isEqualTo(1.0);
  }

  @Test
  void serviceAccount_limitedByAllowedKbIds() {
    RagAuthContextHolder.set(new RagAuthContext(null, null, "SERVICE_ACCOUNT", Set.of(3L), Set.of(3L), Set.of(), "service_account", "sa"));

    assertThat(guard.canRead(3L)).isTrue();
    assertThat(guard.canRead(4L)).isFalse();
  }
}
