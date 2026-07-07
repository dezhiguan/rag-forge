package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.Organization;
import com.ragforge.model.vo.KnowledgeBaseVO;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.OrgService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseServiceImplTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private com.ragforge.mapper.DocumentChunkMapper documentChunkMapper;
  @Mock private com.ragforge.security.KbAccessGuard kbAccessGuard;
  @Mock private com.ragforge.mapper.KbAclMapper kbAclMapper;
  @Mock private com.ragforge.mapper.OrgMemberMapper orgMemberMapper;
  @Mock private com.ragforge.mapper.OrganizationMapper organizationMapper;
  @Mock private OrgService orgService;
  @Mock private com.ragforge.mapper.ApiKeyMapper apiKeyMapper;
  @Mock private com.ragforge.mapper.EvalDatasetMapper evalDatasetMapper;
  @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  @InjectMocks private KnowledgeBaseServiceImpl knowledgeBaseService;

  @BeforeEach
  void stubInsertAssignsId() {
    RagAuthContextHolder.set(
        new RagAuthContext(7L, "USER", java.util.Set.of(1L), java.util.Set.of(1L), java.util.Set.of(), "USER", "7"));
    Organization individual = new Organization();
    individual.setId(100L);
    individual.setType("INDIVIDUAL");
    individual.setName("personal");
    individual.setCreatedByUserId(7L);
    when(orgService.ensureIndividualOrg(7L)).thenReturn(individual);
    when(organizationMapper.selectById(100L)).thenReturn(individual);
    when(documentMapper.selectMaps(any())).thenReturn(List.of());
    when(documentChunkMapper.selectMaps(any())).thenReturn(List.of());
    when(kbAclMapper.findAdminKbIds(7L)).thenReturn(List.of());
    when(kbAclMapper.findWritableKbIds(7L)).thenReturn(List.of());
    when(knowledgeBaseMapper.insert(any(KnowledgeBase.class)))
        .thenAnswer(
            invocation -> {
              KnowledgeBase kb = invocation.getArgument(0);
              kb.setId(1L);
              return 1;
            });
  }

  @org.junit.jupiter.api.AfterEach
  void clearAuth() {
    RagAuthContextHolder.clear();
    com.ragforge.security.OrgContextHolder.clear();
    com.ragforge.security.AdminOverrideHolder.clear();
  }

  @Test
  void deleteKbWithDocumentsShouldFail() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb-with-docs");
    KnowledgeBase kb = knowledgeBaseService.create(dto);

    when(knowledgeBaseMapper.selectById(kb.getId())).thenReturn(kb);
    when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

    assertThatThrownBy(() -> knowledgeBaseService.delete(kb.getId()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("1 个文档");
  }

  @Test
  void deleteEmptyKbShouldSoftDelete() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb-empty");
    KnowledgeBase kb = knowledgeBaseService.create(dto);

    when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    when(knowledgeBaseMapper.selectById(kb.getId())).thenReturn(kb);

    knowledgeBaseService.delete(kb.getId());

    ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
    verify(knowledgeBaseMapper).updateById(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("deleted");
  }

  @Test
  void createPersistsActiveKnowledgeBase() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("  new-kb  ");

    KnowledgeBase created = knowledgeBaseService.create(dto);

    assertThat(created.getId()).isEqualTo(1L);
    assertThat(created.getName()).isEqualTo("new-kb");
    assertThat(created.getStatus()).isEqualTo("active");
    verify(knowledgeBaseMapper).insert(eq(created));
  }

  @Test
  void listAll_loadsActiveKnowledgeBases() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(10L);
    kb.setName("cached-kb");
    kb.setStatus("active");
    kb.setCreatedAt(LocalDateTime.now());
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(kb));

    List<KnowledgeBaseVO> first = knowledgeBaseService.listAll();
    List<KnowledgeBaseVO> second = knowledgeBaseService.listAll();

    assertThat(first).hasSize(1);
    assertThat(first.get(0).getName()).isEqualTo("cached-kb");
    assertThat(second).isSameAs(first);
  }

  @Test
  void getById_returnsVoForActiveKb() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(20L);
    kb.setName("found");
    kb.setStatus("active");
    when(knowledgeBaseMapper.selectById(20L)).thenReturn(kb);

    KnowledgeBaseVO vo = knowledgeBaseService.getById(20L);

    assertThat(vo.getId()).isEqualTo(20L);
    assertThat(vo.getName()).isEqualTo("found");
  }

  @Test
  void getById_missingOrDeletedThrows404() {
    when(knowledgeBaseMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> knowledgeBaseService.getById(404L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(404);
  }

  @Test
  void updateAppliesProvidedFields() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(30L);
    kb.setName("old");
    kb.setStatus("active");
    when(knowledgeBaseMapper.selectById(30L)).thenReturn(kb);

    UpdateKbDTO dto = new UpdateKbDTO();
    dto.setName("  new-name  ");
    dto.setDescription("desc");
    dto.setChunkSize(256);
    dto.setChunkOverlap(32);

    KnowledgeBase updated = knowledgeBaseService.update(30L, dto);

    assertThat(updated.getName()).isEqualTo("new-name");
    assertThat(updated.getDescription()).isEqualTo("desc");
    assertThat(updated.getChunkSize()).isEqualTo(256);
    assertThat(updated.getChunkOverlap()).isEqualTo(32);
    verify(knowledgeBaseMapper).updateById(kb);
  }

  @Test
  void createAppliesAnswerConfigWhenProvided() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb");
    dto.setAnswerMode("OFF");
    dto.setAnswerModel("qwen-max");

    KnowledgeBase created = knowledgeBaseService.create(dto);

    assertThat(created.getAnswerMode()).isEqualTo("OFF");
    assertThat(created.getAnswerModel()).isEqualTo("qwen-max");
  }

  @Test
  void createRejectsBlankAnswerModel() {
    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("kb");
    dto.setAnswerModel("   ");

    assertThatThrownBy(() -> knowledgeBaseService.create(dto))
        .isInstanceOf(com.ragforge.common.BizException.class)
        .hasMessage("answerModel 不能为空");
  }

  @Test
  void updateRejectsInvalidAnswerMode() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(40L);
    kb.setName("old");
    kb.setStatus("active");
    when(knowledgeBaseMapper.selectById(40L)).thenReturn(kb);

    UpdateKbDTO dto = new UpdateKbDTO();
    dto.setAnswerMode("invalid");

    assertThatThrownBy(() -> knowledgeBaseService.update(40L, dto))
        .isInstanceOf(com.ragforge.common.BizException.class)
        .hasMessage("answerMode 只能是 OFF / PREVIEW / ON");
  }

  @Test
  void createTeamKbRequiresOrgAdminAndAllowsOrgVisibility() {
    Organization team = new Organization();
    team.setId(200L);
    team.setType("TEAM");
    team.setName("team");
    when(organizationMapper.selectById(200L)).thenReturn(team);
    when(orgMemberMapper.isOrgAdmin(200L, 7L)).thenReturn(true);

    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("team-kb");
    dto.setOrgId(200L);
    dto.setVisibility("org");
    dto.setImageProcessingMode("on");

    KnowledgeBase created = knowledgeBaseService.create(dto);

    assertThat(created.getOrgId()).isEqualTo(200L);
    assertThat(created.getVisibility()).isEqualTo("ORG");
    assertThat(created.getImageProcessingMode()).isEqualTo("ON");
  }

  @Test
  void createRejectsInvalidOwnershipAndVisibility() {
    Organization team = new Organization();
    team.setId(201L);
    team.setType("TEAM");
    when(organizationMapper.selectById(201L)).thenReturn(team);
    when(orgMemberMapper.isOrgAdmin(201L, 7L)).thenReturn(false);

    CreateKbDTO dto = new CreateKbDTO();
    dto.setName("team-kb");
    dto.setOrgId(201L);
    dto.setVisibility("ORG");

    assertThatThrownBy(() -> knowledgeBaseService.create(dto))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(403);

    Organization individual = new Organization();
    individual.setId(202L);
    individual.setType("INDIVIDUAL");
    individual.setCreatedByUserId(7L);
    when(organizationMapper.selectById(202L)).thenReturn(individual);
    dto.setOrgId(202L);
    dto.setVisibility("ORG");

    assertThatThrownBy(() -> knowledgeBaseService.create(dto))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(400);
  }

  @Test
  void listVisibleFiltersOrgContextAndResolvesPermissionsAndCounts() {
    com.ragforge.security.OrgContextHolder.set(100L);
    KnowledgeBase owned = kb(1L, "owned", 100L, "PRIVATE", 7L);
    owned.setDocCount(3);
    owned.setChunkCount(30);
    KnowledgeBase publicKb = kb(2L, "public", 999L, "PUBLIC", 99L);
    KnowledgeBase hidden = kb(3L, "hidden", 999L, "ORG", 99L);
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L, 2L, 3L));
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(owned, publicKb, hidden));
    lenient().when(kbAclMapper.findWritableKbIds(7L)).thenReturn(List.of(2L));
    lenient()
        .when(organizationMapper.selectBatchIds(any()))
        .thenReturn(List.of(org(100L, "personal"), org(999L, "other")));

    List<KnowledgeBaseVO> vos = knowledgeBaseService.listVisibleToCurrentUser();

    // 模型 Y：仅当前组织(100)的库保留；他组织的 PUBLIC(2L)/ORG(3L) 不再穿透。
    assertThat(vos).extracting(KnowledgeBaseVO::getId).containsExactly(1L);
    assertThat(vos.get(0).getMyPermission()).isEqualTo("admin");
    // 计数直接读实体冗余字段(不再实时聚合)。
    assertThat(vos.get(0).getDocCount()).isEqualTo(3);
    assertThat(vos.get(0).getChunkCount()).isEqualTo(30);
  }

  @Test
  void listVisiblePagedAppliesKeywordAndBounds() {
    // 关键词/边界过滤已下推到 SQL(selectPage),此处 mock 返回“已过滤”的一页,
    // 验证入参归一化(page 0→1、size 0→10)与 total 透传。
    KnowledgeBase alpha = kb(11L, "alpha", 100L, "PRIVATE", 7L);
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(11L, 12L));
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<KnowledgeBase> mpPage =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
    mpPage.setRecords(List.of(alpha));
    mpPage.setTotal(1);
    when(knowledgeBaseMapper.selectPage(any(), any())).thenReturn(mpPage);

    com.ragforge.common.PageResult<KnowledgeBaseVO> page =
        knowledgeBaseService.listVisiblePaged("AL", 0, 0);

    assertThat(page.getTotal()).isEqualTo(1);
    assertThat(page.getList().getFirst().getName()).isEqualTo("alpha");
    assertThat(page.getPage()).isEqualTo(1);
    assertThat(page.getSize()).isEqualTo(10);
  }

  @Test
  void visibilityImpactDetectsCrossOrgApiKeyReferences() throws Exception {
    KnowledgeBase kb = kb(50L, "public-kb", 100L, "PUBLIC", 7L);
    Organization individual = org(100L, "personal");
    individual.setType("INDIVIDUAL");
    when(knowledgeBaseMapper.selectById(50L)).thenReturn(kb);
    when(organizationMapper.selectById(100L)).thenReturn(individual);
    when(evalDatasetMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
    com.ragforge.model.entity.ApiKey key = new com.ragforge.model.entity.ApiKey();
    key.setId(9L);
    key.setKeyName("prod-key");
    key.setOrgId(300L);
    key.setAllowedKbIds("[50]");
    when(apiKeyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(key));
    when(organizationMapper.selectById(300L)).thenReturn(org(300L, "consumer"));
    when(objectMapper.readValue(eq("[50]"), eq(List.class))).thenReturn(List.of(50));

    com.ragforge.model.vo.VisibilityImpactVo impact =
        knowledgeBaseService.visibilityImpact(50L, "PRIVATE");

    assertThat(impact.isNarrowing()).isTrue();
    assertThat(impact.isHasBlockingDependencies()).isTrue();
    assertThat(impact.getEvalDatasetCount()).isEqualTo(2);
    assertThat(impact.getCrossOrgApiKeys().getFirst().getOrgName()).isEqualTo("consumer");
  }

  @Test
  void changeVisibilityRequiresForceForBlockingNarrowingAndReasonForPublic() throws Exception {
    KnowledgeBase kb = kb(60L, "public-kb", 100L, "PUBLIC", 7L);
    Organization individual = org(100L, "personal");
    individual.setType("INDIVIDUAL");
    when(knowledgeBaseMapper.selectById(60L)).thenReturn(kb);
    when(organizationMapper.selectById(100L)).thenReturn(individual);
    when(evalDatasetMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    com.ragforge.model.entity.ApiKey key = new com.ragforge.model.entity.ApiKey();
    key.setId(10L);
    key.setOrgId(300L);
    key.setAllowedKbIds("[60]");
    when(apiKeyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(key));
    when(objectMapper.readValue(eq("[60]"), eq(List.class))).thenReturn(List.of(60));

    com.ragforge.model.dto.ChangeVisibilityDTO dto =
        new com.ragforge.model.dto.ChangeVisibilityDTO();
    dto.setVisibility("PRIVATE");

    assertThatThrownBy(() -> knowledgeBaseService.changeVisibility(60L, dto))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(409);

    dto.setForce(true);
    KnowledgeBase changed = knowledgeBaseService.changeVisibility(60L, dto);

    assertThat(changed.getVisibility()).isEqualTo("PRIVATE");
    verify(knowledgeBaseMapper).updateById(kb);

    kb.setVisibility("PRIVATE");
    com.ragforge.model.dto.ChangeVisibilityDTO open =
        new com.ragforge.model.dto.ChangeVisibilityDTO();
    open.setVisibility("PUBLIC");
    assertThatThrownBy(() -> knowledgeBaseService.changeVisibility(60L, open))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(400);
  }

  private static KnowledgeBase kb(Long id, String name, Long orgId, String visibility, Long ownerUserId) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setName(name);
    kb.setOrgId(orgId);
    kb.setVisibility(visibility);
    kb.setOwnerUserId(ownerUserId);
    kb.setStatus("active");
    kb.setCreatedAt(LocalDateTime.now());
    return kb;
  }

  private static Organization org(Long id, String name) {
    Organization org = new Organization();
    org.setId(id);
    org.setName(name);
    return org;
  }
}
