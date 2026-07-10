package com.ragforge.auth;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注销后台清理任务：扫描 deletion_scheduled_at 到期的账号，执行 PIPL 合规数据清理。
 * ShedLock 防止多副本重复执行；每天凌晨 2 点运行。
 */
@Component
@Slf4j
public class AccountDeletionCleanupJob {

  private final JdbcTemplate jdbcTemplate;

  public AccountDeletionCleanupJob(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Scheduled(cron = "0 0 2 * * *")
  @SchedulerLock(name = "account-deletion-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
  public void run() {
    List<Map<String, Object>> dueUsers = jdbcTemplate.queryForList("""
        SELECT id FROM auth_users
        WHERE status = 'PENDING_DELETION'
        AND deletion_scheduled_at <= now()
        """);
    if (dueUsers.isEmpty()) {
      return;
    }
    log.info("[AccountDeletion] Found {} account(s) due for cleanup", dueUsers.size());
    for (Map<String, Object> row : dueUsers) {
      long userId = ((Number) row.get("id")).longValue();
      try {
        cleanupUser(userId);
        log.info("[AccountDeletion] Cleaned up user {}", userId);
      } catch (Exception e) {
        log.error("[AccountDeletion] Failed to cleanup user {}: {}", userId, e.getMessage(), e);
      }
    }
  }

  @Transactional
  public void cleanupUser(long userId) {
    // 1. 吊销 API keys
    jdbcTemplate.update("UPDATE api_keys SET status = 'REVOKED' WHERE user_id = ?", userId);
    // 2. 移出所有组织
    jdbcTemplate.update("DELETE FROM org_members WHERE user_id = ?", userId);
    // 3. 匿名化用户个人信息（PIPL：删除可识别信息）
    jdbcTemplate.update("""
        UPDATE auth_users SET
            phone_hash = NULL,
            email_hash = NULL,
            password_hash = NULL,
            username = CONCAT('deleted_user_', CAST(id AS VARCHAR)),
            status = 'DELETED',
            pending_deletion_at = NULL,
            deletion_scheduled_at = NULL
        WHERE id = ?
        """, userId);
    // 4. 清理 user_profile 可识别字段（保留行关联）
    jdbcTemplate.update("""
        UPDATE user_profile SET
            display_name = NULL,
            avatar = NULL,
            bio = NULL
        WHERE auth_user_id = ?
        """, userId);
  }
}
