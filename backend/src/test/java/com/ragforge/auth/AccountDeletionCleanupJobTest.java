package com.ragforge.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AccountDeletionCleanupJobTest {

  @Mock private JdbcTemplate jdbcTemplate;

  @InjectMocks private AccountDeletionCleanupJob job;

  @Test
  void run_noUsersdue_doesNothing() {
    when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

    job.run();

    verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
  }

  @Test
  void run_usersDue_cleansUpEach() {
    when(jdbcTemplate.queryForList(anyString()))
        .thenReturn(List.of(Map.of("id", 101L), Map.of("id", 102L)));
    when(jdbcTemplate.update(anyString(), any(Object.class))).thenReturn(1);

    job.run();

    // each user: 4 UPDATE/DELETE statements
    verify(jdbcTemplate, times(8)).update(anyString(), any(Object.class));
  }

  @Test
  void cleanupUser_revokesApiKeys() {
    when(jdbcTemplate.update(anyString(), any(Object.class))).thenReturn(1);

    job.cleanupUser(42L);

    verify(jdbcTemplate).update(contains("UPDATE api_keys SET status = 'REVOKED'"), eq(42L));
  }

  @Test
  void cleanupUser_removesOrgMembers() {
    when(jdbcTemplate.update(anyString(), any(Object.class))).thenReturn(1);

    job.cleanupUser(42L);

    verify(jdbcTemplate).update(contains("DELETE FROM org_members"), eq(42L));
  }

  @Test
  void cleanupUser_anonymizesAuthUser() {
    when(jdbcTemplate.update(anyString(), any(Object.class))).thenReturn(1);

    job.cleanupUser(42L);

    verify(jdbcTemplate).update(contains("UPDATE auth_users SET"), eq(42L));
  }

  @Test
  void cleanupUser_clearsUserProfile() {
    when(jdbcTemplate.update(anyString(), any(Object.class))).thenReturn(1);

    job.cleanupUser(42L);

    verify(jdbcTemplate).update(contains("UPDATE user_profile SET"), eq(42L));
  }

  @Test
  void run_oneUserFails_continuesWithNext() {
    when(jdbcTemplate.queryForList(anyString()))
        .thenReturn(List.of(Map.of("id", 201L), Map.of("id", 202L)));
    // First user's api_keys update throws; second should still be processed
    when(jdbcTemplate.update(contains("api_keys"), eq(201L)))
        .thenThrow(new RuntimeException("DB error"));
    when(jdbcTemplate.update(anyString(), eq(202L))).thenReturn(1);

    // Should not throw
    job.run();

    // api_keys update attempted for both users
    verify(jdbcTemplate).update(contains("api_keys"), eq(201L));
    verify(jdbcTemplate).update(contains("api_keys"), eq(202L));
  }
}
