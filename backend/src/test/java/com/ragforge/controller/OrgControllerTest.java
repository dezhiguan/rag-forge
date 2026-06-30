package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.service.InvitationService;
import com.ragforge.service.OrgService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class OrgControllerTest {

  @Mock private OrgService orgService;
  @Mock private InvitationService invitationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new OrgController(orgService, invitationService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void createOrg_returnsOkWithOrgData() throws Exception {
    when(orgService.createOrganization(anyString(), anyString()))
        .thenReturn(Map.of("id", 1L, "slug", "my-org", "name", "My Org"));

    mockMvc
        .perform(
            post("/api/v1/orgs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"my-org\",\"name\":\"My Org\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.slug").value("my-org"));
  }

  @Test
  void listMine_returnsOrgList() throws Exception {
    when(orgService.listMyOrganizations()).thenReturn(List.of(
        Map.of("id", 1L, "name", "Personal"),
        Map.of("id", 2L, "name", "Team")
    ));

    mockMvc
        .perform(get("/api/v1/orgs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void detail_returnsOrgDetails() throws Exception {
    when(orgService.getOrganization(5L)).thenReturn(Map.of("id", 5L, "name", "Engineering"));

    mockMvc
        .perform(get("/api/v1/orgs/5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(5));
  }

  @Test
  void update_patchesOrgNameAndSlug() throws Exception {
    when(orgService.updateOrganization(eq(5L), anyString(), anyString()))
        .thenReturn(Map.of("id", 5L, "name", "Eng Updated", "slug", "eng-updated"));

    mockMvc
        .perform(
            patch("/api/v1/orgs/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Eng Updated\",\"slug\":\"eng-updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Eng Updated"));
  }

  @Test
  void upgradeToTeam_returns200() throws Exception {
    doNothing().when(orgService).upgradeToTeam(5L);

    mockMvc
        .perform(post("/api/v1/orgs/5/upgrade"))
        .andExpect(status().isOk());

    verify(orgService).upgradeToTeam(5L);
  }

  @Test
  void transferOwner_delegates() throws Exception {
    doNothing().when(orgService).transferOwnership(5L, 99L);

    mockMvc
        .perform(
            post("/api/v1/orgs/5/transfer-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":99}"))
        .andExpect(status().isOk());

    verify(orgService).transferOwnership(5L, 99L);
  }

  @Test
  void deleteOrg_delegates() throws Exception {
    doNothing().when(orgService).deleteOrganization(5L);

    mockMvc
        .perform(delete("/api/v1/orgs/5"))
        .andExpect(status().isOk());

    verify(orgService).deleteOrganization(5L);
  }

  @Test
  void leaveOrg_delegates() throws Exception {
    doNothing().when(orgService).leaveOrganization(5L);

    mockMvc
        .perform(post("/api/v1/orgs/5/leave"))
        .andExpect(status().isOk());

    verify(orgService).leaveOrganization(5L);
  }

  @Test
  void listMembers_returnsMemberList() throws Exception {
    when(orgService.listMembers(5L)).thenReturn(List.of(
        Map.of("userId", 1L, "role", "OWNER"),
        Map.of("userId", 2L, "role", "MEMBER")
    ));

    mockMvc
        .perform(get("/api/v1/orgs/5/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void addMember_delegates() throws Exception {
    doNothing().when(orgService).addMember(5L, 20L, "MEMBER");

    mockMvc
        .perform(
            post("/api/v1/orgs/5/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":20,\"role\":\"MEMBER\"}"))
        .andExpect(status().isOk());

    verify(orgService).addMember(5L, 20L, "MEMBER");
  }

  @Test
  void updateMember_delegates() throws Exception {
    doNothing().when(orgService).updateMemberRole(5L, 20L, "ADMIN");

    mockMvc
        .perform(
            patch("/api/v1/orgs/5/members/20")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk());

    verify(orgService).updateMemberRole(5L, 20L, "ADMIN");
  }

  @Test
  void removeMember_delegates() throws Exception {
    doNothing().when(orgService).removeMember(5L, 20L);

    mockMvc
        .perform(delete("/api/v1/orgs/5/members/20"))
        .andExpect(status().isOk());

    verify(orgService).removeMember(5L, 20L);
  }

  @Test
  void invite_createsInvitation() throws Exception {
    when(invitationService.invite(5L, "13800000000", "MEMBER"))
        .thenReturn(Map.of("id", 77L, "phone", "13800000000", "role", "MEMBER"));

    mockMvc
        .perform(
            post("/api/v1/orgs/5/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(77));
  }

  @Test
  void orgInvitations_returnsPending() throws Exception {
    when(invitationService.orgPending(5L)).thenReturn(List.of(
        Map.of("id", 77L, "phone", "138****0000")
    ));

    mockMvc
        .perform(get("/api/v1/orgs/5/invitations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
  }

  @Test
  void revokeInvitation_delegates() throws Exception {
    doNothing().when(invitationService).revoke(5L, 77L);

    mockMvc
        .perform(delete("/api/v1/orgs/5/invitations/77"))
        .andExpect(status().isOk());

    verify(invitationService).revoke(5L, 77L);
  }

  @Test
  void memberCandidates_returnsSearchResults() throws Exception {
    when(orgService.searchMemberCandidates(5L, "alice")).thenReturn(List.of(
        Map.of("userId", 3L, "username", "alice")
    ));

    mockMvc
        .perform(get("/api/v1/orgs/5/member-candidates?q=alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].username").value("alice"));
  }
}
