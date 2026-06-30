package com.ragforge.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.ragforge.mapper.AdminAccessAuditMapper;
import com.ragforge.model.entity.AdminAccessAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AdminAccessAuditServiceTest {

  @Mock private AdminAccessAuditMapper auditMapper;

  @InjectMocks private AdminAccessAuditService service;

  @Test
  void recordKbBreakGlass_insertsAuditRow() {
    service.recordKbBreakGlass(42L, "manual override for urgent fix");

    ArgumentCaptor<AdminAccessAudit> captor = ArgumentCaptor.forClass(AdminAccessAudit.class);
    verify(auditMapper).insert(captor.capture());
    AdminAccessAudit row = captor.getValue();
    assertThat(row.getAdminUserId()).isEqualTo(42L);
    assertThat(row.getAction()).isEqualTo("kb_break_glass");
    assertThat(row.getReason()).isEqualTo("manual override for urgent fix");
    assertThat(row.getCreatedAt()).isNotNull();
  }

  @Test
  void recordKbBreakGlass_nullReason_insertsNullReason() {
    service.recordKbBreakGlass(99L, null);

    ArgumentCaptor<AdminAccessAudit> captor = ArgumentCaptor.forClass(AdminAccessAudit.class);
    verify(auditMapper).insert(captor.capture());
    assertThat(captor.getValue().getReason()).isNull();
  }

  @Test
  void recordKbBreakGlass_longReason_truncatesTo512() {
    String longReason = "x".repeat(600);
    service.recordKbBreakGlass(1L, longReason);

    ArgumentCaptor<AdminAccessAudit> captor = ArgumentCaptor.forClass(AdminAccessAudit.class);
    verify(auditMapper).insert(captor.capture());
    assertThat(captor.getValue().getReason()).hasSize(512);
  }

  @Test
  void recordKbBreakGlass_persistFailure_doesNotThrow() {
    doThrow(new RuntimeException("DB offline")).when(auditMapper).insert(any(AdminAccessAudit.class));

    // Should swallow the exception (audit failure must not block business request)
    service.recordKbBreakGlass(1L, "test reason");
  }
}
