package com.ragforge.controller;

import com.ragforge.auth.CapabilityResolver;
import com.ragforge.auth.UserProfileService;
import com.ragforge.common.Result;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录用户聚合信息：身份(JWT) + 本地资料 + 能力清单。 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

  private final UserProfileService userProfileService;
  private final CapabilityResolver capabilityResolver;

  @GetMapping
  public Result<Map<String, Object>> me() {
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null || context.userId() == null) {
      return Result.fail(401, "Unauthorized");
    }
    Long userId = context.userId();
    UserProfile profile = userProfileService.getOrCreate(userId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("userId", userId);
    data.put("tenantId", context.tenantId());
    data.put("ragRole", context.ragRole());
    data.put("platformAdmin", capabilityResolver.isAdmin(context.ragRole()));
    data.put("displayName", userProfileService.resolveDisplayName(profile, userId));
    data.put("avatar", profile.getAvatar());
    data.put("username", profile.getUsername());
    data.put("email", profile.getEmail());
    data.put("maskedPhone", profile.getMaskedPhone());
    data.put("capabilities", capabilityResolver.capabilitiesFor(context.ragRole()));
    return Result.ok(data);
  }
}
