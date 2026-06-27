package com.ragforge.security;

import com.ragforge.web.TraceIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 管理员越权访问审计。当前以结构化日志留痕（who / reason / trace），
 * 后续如需可查询，仅需在此落库（接口不变）。
 */
@Slf4j
@Service
public class AdminAccessAuditService {

  /** 记录一次 ADMIN 破玻璃提权（本次请求获得对全部非 SYSTEM 知识库的访问）。 */
  public void recordKbBreakGlass(Long adminUserId, String reason) {
    log.warn(
        "AUDIT admin_kb_break_glass adminUserId={} reason=\"{}\" traceId={}",
        adminUserId,
        reason == null ? "" : reason.replace('"', '\''),
        TraceIds.current());
  }
}
