package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.auth.UserProfileService;
import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

  @Mock private UserProfileService userProfileService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    RagAuthContextHolder.set(new RagAuthContext(10L, "KB_EDITOR", Set.of(), Set.of(), Set.of(), "USER", "10"));
    mockMvc =
        standaloneSetup(new ProfileController(userProfileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @AfterEach
  void clearAuth() {
    RagAuthContextHolder.clear();
  }

  @Test
  void get_returnsCurrentUserProfile() throws Exception {
    UserProfile profile = buildProfile(10L, "Alice", "Alice Smith", "https://img/avatar.png", "alice@example.com");
    when(userProfileService.getOrCreate(10L)).thenReturn(profile);
    when(userProfileService.resolveDisplayName(profile, 10L)).thenReturn("Alice Smith");

    mockMvc
        .perform(get("/api/v1/profile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.authUserId").value(10))
        .andExpect(jsonPath("$.data.displayName").value("Alice Smith"))
        .andExpect(jsonPath("$.data.email").value("alice@example.com"));
  }

  @Test
  void update_updatesDisplayNameAndAvatar() throws Exception {
    UserProfile updated = buildProfile(10L, "Alice", "New Name", "https://img/new.png", "alice@example.com");
    when(userProfileService.updateProfile(eq(10L), eq("New Name"), eq("https://img/new.png"), eq("my bio")))
        .thenReturn(updated);
    when(userProfileService.resolveDisplayName(updated, 10L)).thenReturn("New Name");

    mockMvc
        .perform(
            put("/api/v1/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"New Name\",\"avatar\":\"https://img/new.png\",\"bio\":\"my bio\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.displayName").value("New Name"));

    verify(userProfileService).updateProfile(10L, "New Name", "https://img/new.png", "my bio");
  }

  @Test
  void get_noAuthContext_throwsIllegalState() throws Exception {
    RagAuthContextHolder.clear();

    mockMvc
        .perform(get("/api/v1/profile"))
        .andExpect(status().isInternalServerError());
  }

  private static UserProfile buildProfile(long id, String username, String displayName, String avatar, String email) {
    UserProfile p = new UserProfile();
    p.setAuthUserId(id);
    p.setUsername(username);
    p.setDisplayName(displayName);
    p.setAvatar(avatar);
    p.setEmail(email);
    return p;
  }
}
