package com.ragforge.auth;

import com.ragforge.mapper.UserProfileMapper;
import com.ragforge.model.entity.UserProfile;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** RAG 本地用户资料读写 + 显示名兜底。 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

  private final UserProfileMapper userProfileMapper;

  /** 取资料；不存在则懒创建一行（仅 auth_user_id）。 */
  public UserProfile getOrCreate(Long authUserId) {
    UserProfile profile = userProfileMapper.selectById(authUserId);
    if (profile == null) {
      profile = new UserProfile();
      profile.setAuthUserId(authUserId);
      profile.setCreatedAt(LocalDateTime.now());
      profile.setUpdatedAt(LocalDateTime.now());
      userProfileMapper.insert(profile);
    }
    return profile;
  }

  /**
   * 按用户名/邮箱/显示名模糊搜索本地用户资料。
   * 关键词不足 2 字符返回空，避免一字母把全表拉出来；上限 limit 条。
   */
  public List<UserProfile> search(String q, int limit) {
    String trimmed = q == null ? "" : q.trim();
    if (trimmed.length() < 2) {
      return List.of();
    }
    String kw = "%" + escapeLike(trimmed) + "%";
    return userProfileMapper.search(kw, Math.max(1, Math.min(limit, 50)));
  }

  /** 转义 LIKE 通配符，避免用户输入的 % 与 _ 被当成通配。 */
  private String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 个人设置：仅更新展示资料（显示名/头像/简介）。 */
  public UserProfile updateProfile(Long authUserId, String displayName, String avatar, String bio) {
    UserProfile profile = getOrCreate(authUserId);
    if (displayName != null) {
      profile.setDisplayName(displayName.trim());
    }
    if (avatar != null) {
      profile.setAvatar(avatar);
    }
    if (bio != null) {
      profile.setBio(bio);
    }
    profile.setUpdatedAt(LocalDateTime.now());
    userProfileMapper.updateById(profile);
    return profile;
  }

  /**
   * 注册/凭证变更后同步展示用身份字段（用户名/邮箱/脱敏手机号）。
   * 仅覆盖非空入参；显示名为空时用用户名兜底。
   */
  public void syncIdentity(Long authUserId, String username, String email, String phone) {
    UserProfile profile = getOrCreate(authUserId);
    if (StringUtils.hasText(username)) {
      profile.setUsername(username.trim());
      if (!StringUtils.hasText(profile.getDisplayName())) {
        profile.setDisplayName(username.trim());
      }
    }
    if (StringUtils.hasText(email)) {
      profile.setEmail(email.trim());
    }
    if (StringUtils.hasText(phone)) {
      profile.setMaskedPhone(maskPhone(phone));
    }
    profile.setUpdatedAt(LocalDateTime.now());
    userProfileMapper.updateById(profile);
  }

  /** 显示名兜底：显示名 > 用户名 > 脱敏手机号 > 用户_{id}。 */
  public String resolveDisplayName(UserProfile profile, Long authUserId) {
    if (profile != null && StringUtils.hasText(profile.getDisplayName())) {
      return profile.getDisplayName();
    }
    if (profile != null && StringUtils.hasText(profile.getUsername())) {
      return profile.getUsername();
    }
    if (profile != null && StringUtils.hasText(profile.getMaskedPhone())) {
      return profile.getMaskedPhone();
    }
    return "用户_" + authUserId;
  }

  private String maskPhone(String phone) {
    String normalized = phone == null ? "" : phone.trim().replace(" ", "").replace("-", "");
    if (normalized.startsWith("+86")) {
      normalized = normalized.substring(3);
    }
    if (normalized.length() != 11) {
      return "****";
    }
    return normalized.substring(0, 3) + "****" + normalized.substring(7);
  }
}
