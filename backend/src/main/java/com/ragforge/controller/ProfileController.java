package com.ragforge.controller;

import com.ragforge.auth.UserProfileService;
import com.ragforge.common.Result;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 个人设置：RAG 本地资料（显示名/头像/简介）。不调网关。 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

  private final UserProfileService userProfileService;

  @GetMapping
  public Result<Map<String, Object>> get() {
    Long userId = currentUserId();
    UserProfile profile = userProfileService.getOrCreate(userId);
    return Result.ok(toView(profile, userId));
  }

  @PutMapping
  public Result<Map<String, Object>> update(@RequestBody UpdateProfileRequest request) {
    Long userId = currentUserId();
    UserProfile profile = userProfileService.updateProfile(
        userId, request.displayName(), request.avatar(), request.bio());
    return Result.ok(toView(profile, userId));
  }

  private Map<String, Object> toView(UserProfile profile, Long userId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("authUserId", userId);
    view.put("displayName", userProfileService.resolveDisplayName(profile, userId));
    view.put("avatar", profile.getAvatar());
    view.put("bio", profile.getBio());
    view.put("username", profile.getUsername());
    view.put("email", profile.getEmail());
    view.put("maskedPhone", profile.getMaskedPhone());
    return view;
  }

  private Long currentUserId() {
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null || context.userId() == null) {
      throw new IllegalStateException("missing auth context");
    }
    return context.userId();
  }

  public record UpdateProfileRequest(String displayName, String avatar, String bio) {}
}
