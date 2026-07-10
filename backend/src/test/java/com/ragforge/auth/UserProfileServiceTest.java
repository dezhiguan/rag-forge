package com.ragforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

import com.ragforge.mapper.UserProfileMapper;
import com.ragforge.model.entity.UserProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

  @Mock private UserProfileMapper userProfileMapper;
  @Mock private JdbcTemplate jdbcTemplate;

  @InjectMocks private UserProfileService service;

  // ---- getOrCreate ----

  @Test
  void getOrCreate_existingProfile_returnsItWithoutInsert() {
    UserProfile existing = profile(1L, "alice", null, null);
    when(userProfileMapper.selectById(1L)).thenReturn(existing);

    UserProfile result = service.getOrCreate(1L);

    assertThat(result).isSameAs(existing);
    verify(userProfileMapper, never()).insert(any(UserProfile.class));
  }

  @Test
  void getOrCreate_noProfile_createsNewAndInsertsIt() {
    when(userProfileMapper.selectById(99L)).thenReturn(null);

    UserProfile result = service.getOrCreate(99L);

    assertThat(result.getAuthUserId()).isEqualTo(99L);
    assertThat(result.getCreatedAt()).isNotNull();
    verify(userProfileMapper).insert(any(UserProfile.class));
  }

  // ---- resolveDisplayName ----

  @Test
  void resolveDisplayName_displayNamePresent_returnsDisplayName() {
    UserProfile p = profile(1L, "alice", "Alice W.", null);
    assertThat(service.resolveDisplayName(p, 1L)).isEqualTo("Alice W.");
  }

  @Test
  void resolveDisplayName_noDisplayName_fallsBackToUsername() {
    UserProfile p = profile(1L, "alice", null, null);
    assertThat(service.resolveDisplayName(p, 1L)).isEqualTo("alice");
  }

  @Test
  void resolveDisplayName_noDisplayNameNoUsername_fallsBackToMaskedPhone() {
    UserProfile p = new UserProfile();
    p.setAuthUserId(1L);
    p.setMaskedPhone("138****1234");
    assertThat(service.resolveDisplayName(p, 1L)).isEqualTo("138****1234");
  }

  @Test
  void resolveDisplayName_nullProfile_returnsUserId() {
    assertThat(service.resolveDisplayName(null, 42L)).isEqualTo("用户_42");
  }

  @Test
  void resolveDisplayName_emptyProfile_returnsUserId() {
    UserProfile empty = new UserProfile();
    assertThat(service.resolveDisplayName(empty, 7L)).isEqualTo("用户_7");
  }

  // ---- search ----

  @Test
  void search_queryTooShort_returnsEmpty() {
    assertThat(service.search("a", 10)).isEmpty();
    assertThat(service.search("", 10)).isEmpty();
    assertThat(service.search(null, 10)).isEmpty();
    verify(userProfileMapper, never()).search(any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void search_queryTwoChars_callsMapper() {
    UserProfile p = profile(1L, "ab", null, null);
    when(userProfileMapper.search("%ab%", 10)).thenReturn(List.of(p));

    List<UserProfile> result = service.search("ab", 10);

    assertThat(result).hasSize(1);
    verify(userProfileMapper).search("%ab%", 10);
  }

  @Test
  void search_queryWithWildcard_escapesPercent() {
    when(userProfileMapper.search("%al\\%ce%", 5)).thenReturn(List.of());

    service.search("al%ce", 5);

    verify(userProfileMapper).search("%al\\%ce%", 5);
  }

  @Test
  void search_limitExceeds50_capsAt50() {
    when(userProfileMapper.search(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

    service.search("alice", 200);

    verify(userProfileMapper).search("%alice%", 50);
  }

  // ---- syncIdentity ----

  @Test
  void syncIdentity_updatesUsernameAndEmail() {
    UserProfile existing = profile(5L, "old", null, null);
    when(userProfileMapper.selectById(5L)).thenReturn(existing);

    service.syncIdentity(5L, "newuser", "new@email.com", null);

    verify(userProfileMapper).updateById(existing);
    assertThat(existing.getUsername()).isEqualTo("newuser");
    assertThat(existing.getEmail()).isEqualTo("new@email.com");
  }

  @Test
  void syncIdentity_setsDisplayNameFromUsernameWhenBlank() {
    UserProfile existing = new UserProfile();
    existing.setAuthUserId(5L);
    when(userProfileMapper.selectById(5L)).thenReturn(existing);

    service.syncIdentity(5L, "freshuser", null, null);

    assertThat(existing.getDisplayName()).isEqualTo("freshuser");
  }

  @Test
  void syncIdentity_masksPhoneCorrectly() {
    UserProfile existing = profile(3L, "bob", null, null);
    when(userProfileMapper.selectById(3L)).thenReturn(existing);

    service.syncIdentity(3L, null, null, "13812345678");

    assertThat(existing.getMaskedPhone()).isEqualTo("138****5678");
  }

  @Test
  void syncIdentity_phoneWithCountryCode_stripped() {
    UserProfile existing = profile(3L, "bob", null, null);
    when(userProfileMapper.selectById(3L)).thenReturn(existing);

    service.syncIdentity(3L, null, null, "+8613812345678");

    assertThat(existing.getMaskedPhone()).isEqualTo("138****5678");
  }

  @Test
  void syncIdentity_shortPhone_masksToStars() {
    UserProfile existing = profile(3L, "bob", null, null);
    when(userProfileMapper.selectById(3L)).thenReturn(existing);

    service.syncIdentity(3L, null, null, "12345");

    assertThat(existing.getMaskedPhone()).isEqualTo("****");
  }

  // ---- updateProfile ----

  @Test
  void updateProfile_updatesDisplayNameAndAvatar() {
    UserProfile existing = profile(2L, "carol", null, null);
    when(userProfileMapper.selectById(2L)).thenReturn(existing);

    UserProfile result = service.updateProfile(2L, "Carol Smith", "https://img/avatar.png", "my bio");

    assertThat(result.getDisplayName()).isEqualTo("Carol Smith");
    assertThat(result.getAvatar()).isEqualTo("https://img/avatar.png");
    assertThat(result.getBio()).isEqualTo("my bio");
  }

  @Test
  void updateProfile_nullParams_doesNotOverwrite() {
    UserProfile existing = profile(2L, "carol", "Carol", null);
    existing.setAvatar("old-avatar");
    when(userProfileMapper.selectById(2L)).thenReturn(existing);

    service.updateProfile(2L, null, null, null);

    assertThat(existing.getDisplayName()).isEqualTo("Carol");
    assertThat(existing.getAvatar()).isEqualTo("old-avatar");
  }

  // ---- markOnboardingComplete ----

  @Test
  void markOnboardingComplete_updatesViaJdbc() {
    service.markOnboardingComplete(7L);

    verify(jdbcTemplate).update(
        anyString(),
        eq(7L));
  }

  private static UserProfile profile(long id, String username, String displayName, String avatar) {
    UserProfile p = new UserProfile();
    p.setAuthUserId(id);
    p.setUsername(username);
    p.setDisplayName(displayName);
    p.setAvatar(avatar);
    return p;
  }
}
