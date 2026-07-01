package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.auth.UserProfileService;
import com.ragforge.common.BizException;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.mapper.OrganizationMapper;
import com.ragforge.mapper.UserProfileMapper;
import com.ragforge.model.entity.OrgMember;
import com.ragforge.model.entity.Organization;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

  @Mock private OrganizationMapper organizationMapper;
  @Mock private OrgMemberMapper orgMemberMapper;
  @Mock private UserProfileMapper userProfileMapper;
  @Mock private UserProfileService userProfileService;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private ApiKeyMapper apiKeyMapper;

  @InjectMocks private OrgService orgService;

  @BeforeEach
  void setAuth() {
    RagAuthContextHolder.set(
        new RagAuthContext(7L, "USER", Set.of(16L), Set.of(16L), Set.of(), "USER", "7"));
  }

  @AfterEach
  void clearAuth() {
    RagAuthContextHolder.clear();
  }

  @Test
  void createOrganization_insertsTeamAndOwner() {
    when(organizationMapper.selectCount(any())).thenReturn(0L);
    when(organizationMapper.insert(any(Organization.class)))
        .thenAnswer(
            invocation -> {
              Organization org = invocation.getArgument(0);
              org.setId(16L);
              return 1;
            });

    Map<String, Object> result = orgService.createOrganization("team-one", " Team One ");

    assertThat(result)
        .containsEntry("id", 16L)
        .containsEntry("slug", "team-one")
        .containsEntry("name", "Team One")
        .containsEntry("type", "TEAM")
        .containsEntry("personal", false)
        .containsEntry("myRole", "OWNER");
    ArgumentCaptor<OrgMember> owner = ArgumentCaptor.forClass(OrgMember.class);
    verify(orgMemberMapper).insert(owner.capture());
    assertThat(owner.getValue().getOrgId()).isEqualTo(16L);
    assertThat(owner.getValue().getUserId()).isEqualTo(7L);
    assertThat(owner.getValue().getRole()).isEqualTo("OWNER");
  }

  @Test
  void createOrganization_rejectsInvalidInputAndTakenSlug() {
    assertThatThrownBy(() -> orgService.createOrganization("UPPER", "Team"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));

    assertThatThrownBy(() -> orgService.createOrganization("team-one", " "))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));

    when(organizationMapper.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> orgService.createOrganization("team-one", "Team"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
  }

  @Test
  void createOrganization_rejectsOverlongName_with400NotDbError() {
    // 超长 name 应在 service 层返回 400，而不是撞 organizations.name VARCHAR(128) 报 500。
    String tooLong = "组".repeat(129);
    assertThatThrownBy(() -> orgService.createOrganization("team-one", tooLong))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    verify(organizationMapper, never()).insert(any(com.ragforge.model.entity.Organization.class));
  }

  @Test
  void ensureIndividualOrg_returnsExistingOrCreatesOwnerOrg() {
    Organization existing = org(100L, "u-7-old", "个人组织", "INDIVIDUAL");
    when(organizationMapper.selectOne(any())).thenReturn(existing);
    assertThat(orgService.ensureIndividualOrg(7L)).isSameAs(existing);
    verify(organizationMapper, never()).insert(any(Organization.class));

    when(organizationMapper.selectOne(any())).thenReturn(null);
    when(organizationMapper.insert(any(Organization.class)))
        .thenAnswer(
            invocation -> {
              Organization org = invocation.getArgument(0);
              org.setId(101L);
              return 1;
            });
    Organization created = orgService.ensureIndividualOrg(7L);

    assertThat(created.getId()).isEqualTo(101L);
    assertThat(created.getSlug()).startsWith("u-7-");
    assertThat(created.getType()).isEqualTo("INDIVIDUAL");
    verify(orgMemberMapper).insert(any(OrgMember.class));
  }

  @Test
  void upgradeToTeam_updatesOnlyIndividualOwnerOrg() {
    Organization individual = org(16L, "u-7-abc123", "个人组织", "INDIVIDUAL");
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("OWNER");
    when(organizationMapper.selectById(16L)).thenReturn(individual);

    orgService.upgradeToTeam(16L);

    assertThat(individual.getType()).isEqualTo("TEAM");
    verify(organizationMapper).updateById(individual);

    when(organizationMapper.selectById(16L)).thenReturn(org(16L, "team-one", "Team", "TEAM"));
    assertThatThrownBy(() -> orgService.upgradeToTeam(16L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
  }

  @Test
  void listMyOrganizations_addsRoleAndCounts() {
    Organization personal = org(100L, "u-7-abc123", "个人组织", "INDIVIDUAL");
    Organization team = org(16L, "team-one", "Team One", "TEAM");
    when(organizationMapper.selectOne(any())).thenReturn(personal);
    when(orgMemberMapper.findMemberOrgIds(7L)).thenReturn(List.of(100L, 16L));
    when(organizationMapper.selectBatchIds(List.of(100L, 16L))).thenReturn(List.of(personal, team));
    when(orgMemberMapper.findRole(100L, 7L)).thenReturn("OWNER");
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("ADMIN");
    when(orgMemberMapper.selectCount(any())).thenReturn(1L, 3L);
    when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L, 5L);

    List<Map<String, Object>> result = orgService.listMyOrganizations();

    assertThat(result).hasSize(2);
    assertThat(result.get(0))
        .containsEntry("id", 100L)
        .containsEntry("myRole", "OWNER")
        .containsEntry("memberCount", 1L)
        .containsEntry("kbCount", 2L);
    assertThat(result.get(1)).containsEntry("id", 16L).containsEntry("myRole", "ADMIN");
  }

  @Test
  void getOrganization_requiresMembership() {
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false, true);
    assertThatThrownBy(() -> orgService.getOrganization(16L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403));

    when(organizationMapper.selectById(16L)).thenReturn(org(16L, "team-one", "Team One", "TEAM"));
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("MEMBER");
    assertThat(orgService.getOrganization(16L)).containsEntry("myRole", "MEMBER");
  }

  @Test
  void listMembers_resolvesProfiles() {
    OrgMember member = member(1L, 16L, 88L, "MEMBER");
    UserProfile profile = profile(88L, "Amy");
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(true);
    when(orgMemberMapper.listByOrg(16L)).thenReturn(List.of(member));
    when(userProfileMapper.selectBatchIds(List.of(88L))).thenReturn(List.of(profile));
    when(userProfileService.resolveDisplayName(profile, 88L)).thenReturn("Amy");

    List<Map<String, Object>> result = orgService.listMembers(16L);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst())
        .containsEntry("userId", 88L)
        .containsEntry("role", "MEMBER")
        .containsEntry("displayName", "Amy")
        .containsEntry("email", "amy@example.com");
  }

  @Test
  void searchMemberCandidates_marksExistingMembers() {
    UserProfile profile = profile(88L, "Amy");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(userProfileService.search("am", 10)).thenReturn(List.of(profile));
    when(userProfileService.resolveDisplayName(profile, 88L)).thenReturn("Amy");
    when(orgMemberMapper.isMember(16L, 88L)).thenReturn(true);

    List<Map<String, Object>> result = orgService.searchMemberCandidates(16L, "am");

    assertThat(result).hasSize(1);
    assertThat(result.getFirst())
        .containsEntry("userId", 88L)
        .containsEntry("displayName", "Amy")
        .containsEntry("alreadyMember", true);
  }

  @Test
  void addMember_validatesRoleTargetAndExistingMember() {
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(organizationMapper.selectById(16L)).thenReturn(org(16L, "team-one", "Team", "TEAM"));

    assertThatThrownBy(() -> orgService.addMember(16L, 88L, "bad-role"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    assertThatThrownBy(() -> orgService.addMember(16L, null, "MEMBER"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));

    when(orgMemberMapper.isMember(16L, 88L)).thenReturn(true);
    assertThatThrownBy(() -> orgService.addMember(16L, 88L, "MEMBER"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
  }

  @Test
  void addMember_insertsMemberAndOnlyOwnerCanGrantOwner() {
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(organizationMapper.selectById(16L)).thenReturn(org(16L, "team-one", "Team", "TEAM"));
    when(orgMemberMapper.isMember(16L, 88L)).thenReturn(false);

    orgService.addMember(16L, 88L, "member");

    ArgumentCaptor<OrgMember> inserted = ArgumentCaptor.forClass(OrgMember.class);
    verify(orgMemberMapper).insert(inserted.capture());
    assertThat(inserted.getValue().getRole()).isEqualTo("MEMBER");

    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("ADMIN");
    assertThatThrownBy(() -> orgService.addMember(16L, 89L, "OWNER"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403));
  }

  @Test
  void updateMemberRole_protectsOwnersAndUpdatesTarget() {
    OrgMember target = member(2L, 16L, 88L, "MEMBER");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(orgMemberMapper.selectOne(any())).thenReturn(target);
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("OWNER");

    orgService.updateMemberRole(16L, 88L, "ADMIN");

    assertThat(target.getRole()).isEqualTo("ADMIN");
    verify(orgMemberMapper).updateById(target);

    target.setRole("OWNER");
    when(orgMemberMapper.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> orgService.updateMemberRole(16L, 88L, "MEMBER"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
  }

  @Test
  void removeMember_andLeaveOrganizationProtectLastOwner() {
    OrgMember owner = member(2L, 16L, 88L, "OWNER");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(orgMemberMapper.selectOne(any())).thenReturn(owner);
    when(orgMemberMapper.selectCount(any())).thenReturn(1L);

    assertThatThrownBy(() -> orgService.removeMember(16L, 88L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));

    OrgMember me = member(7L, 16L, 7L, "MEMBER");
    when(orgMemberMapper.selectOne(any())).thenReturn(me);
    orgService.leaveOrganization(16L);
    verify(orgMemberMapper).deleteById(7L);
  }

  @Test
  void transferOwnership_updatesBothMembers() {
    OrgMember target = member(2L, 16L, 88L, "ADMIN");
    OrgMember me = member(7L, 16L, 7L, "OWNER");
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("OWNER");
    when(orgMemberMapper.selectOne(any())).thenReturn(target, me);

    orgService.transferOwnership(16L, 88L);

    assertThat(target.getRole()).isEqualTo("OWNER");
    assertThat(me.getRole()).isEqualTo("ADMIN");
    verify(orgMemberMapper, times(2)).updateById(any(OrgMember.class));

    assertThatThrownBy(() -> orgService.transferOwnership(16L, 7L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
  }

  @Test
  void updateOrganization_validatesSlugUniqueness() {
    Organization org = org(16L, "team-one", "Team", "TEAM");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(organizationMapper.selectById(16L)).thenReturn(org);
    when(organizationMapper.selectCount(any())).thenReturn(0L);
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("ADMIN");

    Map<String, Object> result = orgService.updateOrganization(16L, "New Name", "team-two");

    assertThat(result).containsEntry("slug", "team-two").containsEntry("name", "New Name");
    verify(organizationMapper).updateById(org);

    assertThatThrownBy(() -> orgService.updateOrganization(16L, null, "BadSlug"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
  }

  @Test
  void deleteOrganization_cleansApiKeysWhenNoKbs() {
    when(orgMemberMapper.findRole(16L, 7L)).thenReturn("OWNER");
    when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);

    orgService.deleteOrganization(16L);

    verify(apiKeyMapper).delete(any());
    // 删除组织前须解除软删知识库的 org_id 外键引用，否则删除会因外键约束报 500。
    verify(knowledgeBaseMapper).update(isNull(), any());
    verify(organizationMapper).deleteById(16L);

    when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);
    assertThatThrownBy(() -> orgService.deleteOrganization(16L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
  }

  @Test
  void currentUserRequired() {
    RagAuthContextHolder.clear();

    assertThatThrownBy(() -> orgService.listMyOrganizations())
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(401));
  }

  private static Organization org(Long id, String slug, String name, String type) {
    Organization org = new Organization();
    org.setId(id);
    org.setSlug(slug);
    org.setName(name);
    org.setType(type);
    org.setCreatedByUserId(7L);
    org.setCreatedAt(LocalDateTime.now().minusDays(1));
    org.setUpdatedAt(LocalDateTime.now());
    return org;
  }

  private static OrgMember member(Long id, Long orgId, Long userId, String role) {
    OrgMember member = new OrgMember();
    member.setId(id);
    member.setOrgId(orgId);
    member.setUserId(userId);
    member.setRole(role);
    member.setCreatedAt(LocalDateTime.now().minusDays(1));
    return member;
  }

  private static UserProfile profile(Long userId, String displayName) {
    UserProfile profile = new UserProfile();
    profile.setAuthUserId(userId);
    profile.setDisplayName(displayName);
    profile.setEmail(displayName.toLowerCase() + "@example.com");
    profile.setMaskedPhone("138****0000");
    return profile;
  }
}
