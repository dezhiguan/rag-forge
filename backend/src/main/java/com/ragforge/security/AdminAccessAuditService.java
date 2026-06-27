package com.ragforge.security;

import com.ragforge.mapper.AdminAccessAuditMapper;
import com.ragforge.model.entity.AdminAccessAudit;
import com.ragforge.web.TraceIds;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 管理员越权访问审计：结构化日志 + 落库（admin_access_audit）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccessAuditService {

  private static final String ACTION_KB_BREAK_GLASS = "kb_break_glass";

  private final AdminAccessAuditMapper auditMapper;

  /** 记录一次 ADMIN 破玻璃提权（本次请求获得对全部非 SYSTEM 知识库的访问）。 */
  public void recordKbBreakGlass(Long adminUserId, String reason) {
    String traceId = TraceIds.current();
    log.warn(
        "AUDIT admin_kb_break_glass adminUserId={} reason=\"{}\" traceId={}",
        adminUserId,
        reason == null ? "" : reason.replace('"', '\''),
        traceId);
    try {
      AdminAccessAudit row = new AdminAccessAudit();
      row.setAdminUserId(adminUserId);
      row.setAction(ACTION_KB_BREAK_GLASS);
      row.setReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 512)));
      row.setTraceId(traceId);
      row.setCreatedAt(LocalDateTime.now());
      auditMapper.insert(row);
    } catch (RuntimeException ex) {
      // 审计落库失败不应阻断业务请求；日志已留底。
      log.error("admin access audit persist failed: {}", ex.getMessage());
    }
  }
}
