package com.ragforge.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.auth.CapabilityResolver;
import com.ragforge.auth.UserProfileService;
import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class MeControllerTest {

  @Mock private UserProfileService userProfileService;
  @Mock private CapabilityResolver capabilityResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new MeController(userProfileService, capabilityResolver))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @AfterEach
  void clearAuth() {
    RagAuthContextHolder.clear();
  }

  @Test
  void me_withValidAuthContext_returnsUserData() throws Exception {
    RagAuthContextHolder.set(new RagAuthContext(5L, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", "5"));

    UserProfile profile = new UserProfile();
    profile.setAuthUserId(5L);
    profile.setDisplayName("Admin User");
    profile.setUsername("admin");
    profile.setEmail("admin@example.com");

    when(userProfileService.getOrCreate(5L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(profile, 5L)).thenReturn("Admin User");
    when(capabilityResolver.isAdmin("ADMIN")).thenReturn(true);
    when(capabilityResolver.capabilitiesFor("ADMIN")).thenReturn(
        List.of("dashboard:read", "platform:admin"));

    mockMvc
        .perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(5))
        .andExpect(jsonPath("$.data.ragRole").value("ADMIN"))
        .andExpect(jsonPath("$.data.platformAdmin").value(true))
        .andExpect(jsonPath("$.data.displayName").value("Admin User"))
        .andExpect(jsonPath("$.data.email").value("admin@example.com"));
  }

  @Test
  void me_withNoAuthContext_returns401() throws Exception {
    // 无鉴权上下文：MeController 抛 BizException(401)，GlobalExceptionHandler 按 code 写 HTTP 401，
    // 让前端 axios 走统一"静默续期+重放"（见 7d89bac 会话静默续期加固）。
    mockMvc
        .perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void me_regularUser_returnsCorrectCapabilities() throws Exception {
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(), Set.of(), Set.of(), "USER", "7"));

    UserProfile profile = new UserProfile();
    profile.setAuthUserId(7L);
    profile.setUsername("viewer");

    when(userProfileService.getOrCreate(7L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(profile, 7L)).thenReturn("viewer");
    when(capabilityResolver.isAdmin("KB_VIEWER")).thenReturn(false);
    when(capabilityResolver.capabilitiesFor("KB_VIEWER")).thenReturn(List.of("dashboard:read", "kb:read"));

    mockMvc
        .perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.platformAdmin").value(false))
        .andExpect(jsonPath("$.data.ragRole").value("KB_VIEWER"));
  }
}
