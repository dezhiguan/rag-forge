package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.KnowledgeBaseService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

  private static final String STATUS_DELETED = "deleted";
  private static final String STATUS_ACTIVE = "active";
  private static final long LIST_CACHE_TTL_MS = 10_000L;
  private static final Set<String> VALID_ANSWER_MODES = Set.of("OFF", "PREVIEW", "ON");

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final com.ragforge.mapper.DocumentChunkMapper documentChunkMapper;
  private final com.ragforge.security.KbAccessGuard kbAccessGuard;
  private final com.ragforge.mapper.KbAclMapper kbAclMapper;
  private final com.ragforge.mapper.OrgMemberMapper orgMemberMapper;
  private final com.ragforge.mapper.OrganizationMapper organizationMapper;
  private final com.ragforge.service.OrgService orgService;
  private volatile ListCache listCache;

  @Override
  @Transactional
  public KnowledgeBase create(CreateKbDTO dto) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setName(dto.getName().trim());
    kb.setDescription(dto.getDescription());
    kb.setEmbeddingModel("qwen3-vl-embedding");
    kb.setChunkSize(dto.getChunkSize() != null ? dto.getChunkSize() : 512);
    kb.setChunkOverlap(dto.getChunkOverlap() != null ? dto.getChunkOverlap() : 64);
    kb.setDocCount(0);
    kb.setChunkCount(0);
    kb.setStatus(STATUS_ACTIVE);
    RagAuthContext auth = RagAuthContextHolder.get();
    Long creatorId = auth == null || auth.userId() == null ? 0L : auth.userId();
    kb.setOwnerUserId(creatorId);
    applyOwnership(kb, dto.getOrgId(), dto.getVisibility(), creatorId);
    kb.setKbType("GENERAL");
    kb.setImageProcessingMode(normalizeImageMode(dto.getImageProcessingMode(), "OFF"));
    applyAnswerConfig(kb, dto.getAnswerMode(), dto.getAnswerModel());
    LocalDateTime now = LocalDateTime.now();
    kb.setCreatedAt(now);
    kb.setUpdatedAt(now);
    knowledgeBaseMapper.insert(kb);
    invalidateListCache();
    return kb;
  }

  /** 设定知识库归属与可见性：个人库(PRIVATE/PUBLIC) 或 组织库(PRIVATE/ORG，需 OWNER/ADMIN)。 */
  private void applyOwnership(KnowledgeBase kb, Long orgId, String visibility, Long creatorId) {
    String v = StringUtils.hasText(visibility) ? visibility.trim().toUpperCase() : "PRIVATE";
    // Claude Code 式：个人库进创建者的个人组织（INDIVIDUAL），不再 org_id=null。
    if (orgId == null) {
      orgId = orgService.ensureIndividualOrg(creatorId).getId();
    }
    com.ragforge.model.entity.Organization org = organizationMapper.selectById(orgId);
    if (org == null) {
      throw new BizException(404, "ORG_NOT_FOUND");
    }
    boolean individual = "INDIVIDUAL".equals(org.getType());
    if (individual) {
      // 个人组织：仅本人；保留"个人库可公开"的语义（PRIVATE/PUBLIC）。
      if (creatorId == null || !creatorId.equals(org.getCreatedByUserId())) {
        throw new BizException(403, "NOT_ORG_OWNER");
      }
      if (!"PRIVATE".equals(v) && !"PUBLIC".equals(v)) {
        throw new BizException(400, "KB_VISIBILITY_INVALID");
      }
    } else {
      // 团队组织：仅 OWNER/ADMIN；不允许直接 PUBLIC，避免误把企业资料公开。
      if (creatorId == null || creatorId == 0L || !orgMemberMapper.isOrgAdmin(orgId, creatorId)) {
        throw new BizException(403, "NOT_ORG_ADMIN");
      }
      if (!"PRIVATE".equals(v) && !"ORG".equals(v)) {
        throw new BizException(400, "ORG_KB_VISIBILITY_INVALID");
      }
    }
    kb.setOrgId(orgId);
    kb.setVisibility(v);
  }

  @Override
  public List<KnowledgeBaseVO> listAll() {
    ListCache cached = listCache;
    long now = System.currentTimeMillis();
    if (cached != null && now < cached.expiresAtMs()) {
      return cached.value();
    }
    synchronized (this) {
      cached = listCache;
      now = System.currentTimeMillis();
      if (cached != null && now < cached.expiresAtMs()) {
        return cached.value();
      }
      List<KnowledgeBaseVO> value = loadListAll();
      listCache = new ListCache(value, now + LIST_CACHE_TTL_MS);
      return value;
    }
  }

  @Override
  public List<KnowledgeBaseVO> listVisibleToCurrentUser() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    Set<Long> readable = kbAccessGuard.allReadableKbIds();
    if (readable.isEmpty()) {
      return List.of();
    }
    List<KnowledgeBase> kbs =
        knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .in(KnowledgeBase::getId, readable)
                .ne(KnowledgeBase::getStatus, STATUS_DELETED)
                .orderByDesc(KnowledgeBase::getCreatedAt));

    boolean admin = ctx != null && ctx.isAdmin();
    Set<Long> adminIds = Set.of();
    Set<Long> writableIds = Set.of();
    if (!admin && ctx != null && ctx.userId() != null) {
      adminIds = new java.util.HashSet<>(kbAclMapper.findAdminKbIds(ctx.userId()));
      writableIds = new java.util.HashSet<>(kbAclMapper.findWritableKbIds(ctx.userId()));
    }
    final boolean isAdmin = admin;
    final Set<Long> adminSet = adminIds;
    final Set<Long> writableSet = writableIds;
    final Long uid = ctx == null ? null : ctx.userId();
    List<KnowledgeBaseVO> vos =
        kbs.stream()
            .map(
                kb -> {
                  KnowledgeBaseVO vo = KnowledgeBaseVO.fromEntity(kb);
                  vo.setMyPermission(resolvePermission(kb, uid, isAdmin, adminSet, writableSet));
                  return vo;
                })
            .toList();
    enrichOrgNames(vos);
    applyRealCounts(vos);
    return vos;
  }

  /** 批量回填组织库的组织名（个人库不受影响）。 */
  private void enrichOrgNames(List<KnowledgeBaseVO> vos) {
    java.util.Set<Long> orgIds =
        vos.stream()
            .map(KnowledgeBaseVO::getOrgId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    if (orgIds.isEmpty()) {
      return;
    }
    java.util.Map<Long, String> names =
        organizationMapper.selectBatchIds(orgIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    com.ragforge.model.entity.Organization::getId,
                    com.ragforge.model.entity.Organization::getName));
    vos.forEach(
        vo -> {
          if (vo.getOrgId() != null) {
            vo.setOrgName(names.get(vo.getOrgId()));
          }
        });
  }

  private String resolvePermission(
      KnowledgeBase kb, Long uid, boolean isAdmin, Set<Long> adminSet, Set<Long> writableSet) {
    if (isAdmin) {
      return "admin";
    }
    if (uid != null && uid.equals(kb.getOwnerUserId())) {
      return "admin";
    }
    if (adminSet.contains(kb.getId())) {
      return "admin";
    }
    if (writableSet.contains(kb.getId())) {
      return "write";
    }
    return "read";
  }

  /**
   * 用实时聚合的文档/分片数覆盖 VO 上来自实体的冗余计数器，避免计数器漂移（文档失败/删除/replace
   * 等非常规路径会导致 knowledge_bases.doc_count 与实际不一致）导致列表展示错误。批量 group by，避免 N+1。
   */
  private void applyRealCounts(List<KnowledgeBaseVO> vos) {
    if (vos == null || vos.isEmpty()) {
      return;
    }
    List<Long> kbIds =
        vos.stream().map(KnowledgeBaseVO::getId).filter(java.util.Objects::nonNull).toList();
    if (kbIds.isEmpty()) {
      return;
    }
    java.util.Map<Long, Integer> docCounts =
        countByKb(
            documentMapper.selectMaps(
                new QueryWrapper<Document>()
                    .select("kb_id", "count(*) AS cnt")
                    .in("kb_id", kbIds)
                    .groupBy("kb_id")));
    java.util.Map<Long, Integer> chunkCounts =
        countByKb(
            documentChunkMapper.selectMaps(
                new QueryWrapper<DocumentChunk>()
                    .select("kb_id", "count(*) AS cnt")
                    .in("kb_id", kbIds)
                    .groupBy("kb_id")));
    for (KnowledgeBaseVO vo : vos) {
      vo.setDocCount(docCounts.getOrDefault(vo.getId(), 0));
      vo.setChunkCount(chunkCounts.getOrDefault(vo.getId(), 0));
    }
  }

  private java.util.Map<Long, Integer> countByKb(List<java.util.Map<String, Object>> rows) {
    java.util.Map<Long, Integer> result = new java.util.HashMap<>();
    if (rows == null) {
      return result;
    }
    for (java.util.Map<String, Object> row : rows) {
      Object kb = row.get("kb_id");
      Object cnt = row.get("cnt");
      if (kb instanceof Number) {
        result.put(
            ((Number) kb).longValue(), cnt instanceof Number ? ((Number) cnt).intValue() : 0);
      }
    }
    return result;
  }

  private List<KnowledgeBaseVO> loadListAll() {
    List<KnowledgeBase> list =
        knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .ne(KnowledgeBase::getStatus, STATUS_DELETED)
                .orderByDesc(KnowledgeBase::getCreatedAt));
    List<KnowledgeBaseVO> vos = list.stream().map(KnowledgeBaseVO::fromEntity).toList();
    enrichOrgNames(vos);
    applyRealCounts(vos);
    return vos;
  }

  @Override
  public KnowledgeBaseVO getById(Long id) {
    KnowledgeBase kb = requireActiveKb(id);
    KnowledgeBaseVO vo = KnowledgeBaseVO.fromEntity(kb);
    applyRealCounts(java.util.List.of(vo));
    RagAuthContext ctx = RagAuthContextHolder.get();
    boolean admin = ctx != null && ctx.isAdmin();
    Set<Long> adminIds = Set.of();
    Set<Long> writableIds = Set.of();
    if (!admin && ctx != null && ctx.userId() != null) {
      adminIds = new java.util.HashSet<>(kbAclMapper.findAdminKbIds(ctx.userId()));
      writableIds = new java.util.HashSet<>(kbAclMapper.findWritableKbIds(ctx.userId()));
    }
    vo.setMyPermission(resolvePermission(kb, ctx == null ? null : ctx.userId(), admin, adminIds, writableIds));
    if (kb.getOrgId() != null) {
      com.ragforge.model.entity.Organization org = organizationMapper.selectById(kb.getOrgId());
      vo.setOrgName(org == null ? null : org.getName());
    }
    return vo;
  }

  @Override
  @Transactional
  public KnowledgeBase update(Long id, UpdateKbDTO dto) {
    KnowledgeBase kb = requireActiveKb(id);
    if (StringUtils.hasText(dto.getName())) {
      kb.setName(dto.getName().trim());
    }
    if (dto.getDescription() != null) {
      kb.setDescription(dto.getDescription());
    }
    if (dto.getChunkSize() != null) {
      kb.setChunkSize(dto.getChunkSize());
    }
    if (dto.getChunkOverlap() != null) {
      kb.setChunkOverlap(dto.getChunkOverlap());
    }
    if (dto.getAnswerMode() != null || dto.getAnswerModel() != null) {
      applyAnswerConfig(kb, dto.getAnswerMode(), dto.getAnswerModel());
    }
    if (StringUtils.hasText(dto.getImageProcessingMode())) {
      kb.setImageProcessingMode(normalizeImageMode(dto.getImageProcessingMode(), kb.getImageProcessingMode()));
    }
    if (StringUtils.hasText(dto.getStatus())) {
      kb.setStatus(dto.getStatus());
    }
    kb.setUpdatedAt(LocalDateTime.now());
    knowledgeBaseMapper.updateById(kb);
    invalidateListCache();
    return kb;
  }

  @Override
  @Transactional
  public void delete(Long id) {
    KnowledgeBase kb = requireActiveKb(id);
    Long docCount =
        documentMapper.selectCount(
            new LambdaQueryWrapper<Document>().eq(Document::getKbId, id));
    if (docCount != null && docCount > 0) {
      throw new BizException("知识库下存在 " + docCount + " 个文档，请先删除文档");
    }
    kb.setStatus(STATUS_DELETED);
    kb.setUpdatedAt(LocalDateTime.now());
    knowledgeBaseMapper.updateById(kb);
    invalidateListCache();
  }

  private KnowledgeBase requireActiveKb(Long id) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
    if (kb == null || STATUS_DELETED.equals(kb.getStatus())) {
      throw new BizException(404, "知识库不存在");
    }
    return kb;
  }

  private void invalidateListCache() {
    listCache = null;
  }

  private void applyAnswerConfig(KnowledgeBase kb, String answerMode, String answerModel) {
    if (answerMode != null) {
      String normalizedMode = normalizeAnswerMode(answerMode);
      kb.setAnswerMode(normalizedMode);
    }
    if (answerModel != null) {
      if (!StringUtils.hasText(answerModel)) {
        throw new BizException(400, "answerModel 不能为空");
      }
      kb.setAnswerModel(answerModel.trim());
    }
  }

  // 把传入值规整成 ON / OFF；空串或 null 退回 fallback（创建时是 "OFF"，更新时是原值）。
  // 防御性大写化 + 校验，避免前端误传 "on" / "On" 之类的脏数据进库。
  private String normalizeImageMode(String value, String fallback) {
    if (value == null) return fallback;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return fallback;
    String upper = trimmed.toUpperCase();
    if (!"ON".equals(upper) && !"OFF".equals(upper)) {
      throw new BizException(400, "imageProcessingMode 只能是 ON / OFF");
    }
    return upper;
  }

  private String normalizeAnswerMode(String answerMode) {
    String normalized = answerMode == null ? null : answerMode.trim().toUpperCase();
    if (!StringUtils.hasText(normalized) || !VALID_ANSWER_MODES.contains(normalized)) {
      throw new BizException(400, "answerMode 只能是 OFF / PREVIEW / ON");
    }
    return normalized;
  }

  private record ListCache(List<KnowledgeBaseVO> value, long expiresAtMs) {}
}
