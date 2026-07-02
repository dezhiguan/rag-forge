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
  private final com.ragforge.mapper.ApiKeyMapper apiKeyMapper;
  private final com.ragforge.mapper.EvalDatasetMapper evalDatasetMapper;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
  private volatile ListCache listCache;

  private static final org.slf4j.Logger AUDIT =
      org.slf4j.LoggerFactory.getLogger("ragforge.audit");

  /** 可见性开放度：数值越大越开放（PUBLIC=2 > ORG=1 > PRIVATE=0）。 */
  private static int visibilityRank(String v) {
    if (v == null) {
      return 0;
    }
    switch (v.trim().toUpperCase()) {
      case "PUBLIC":
        return 2;
      case "ORG":
        return 1;
      default:
        return 0;
    }
  }

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
      // 模型 Y：个人组织仅本人、仅 PRIVATE（移除全域公开）。
      if (creatorId == null || !creatorId.equals(org.getCreatedByUserId())) {
        throw new BizException(403, "NOT_ORG_OWNER");
      }
      if (!"PRIVATE".equals(v)) {
        throw new BizException(400, "KB_VISIBILITY_INVALID");
      }
    } else {
      // 团队组织：仅 OWNER/ADMIN；仅 PRIVATE/ORG（不允许全域公开）。
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
    kbs = filterByOrgContext(kbs, ctx);

    boolean admin = ctx != null && ctx.isAdmin();
    Set<Long> adminIds = Set.of();
    Set<Long> writableIds = Set.of();
    if (ctx != null && ctx.userId() != null) {
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

  /**
   * 按当前组织上下文(X-Org-Id)过滤可读库：当前组织的库 + PUBLIC 公开库(全平台共享、任何上下文可见)。
   * 破玻璃(全平台)或未指定上下文时不过滤。历史 org_id=null 数据将清理，不做兼容。
   */
  private List<KnowledgeBase> filterByOrgContext(List<KnowledgeBase> kbs, RagAuthContext ctx) {
    if (ctx != null && ctx.isAdmin() && com.ragforge.security.AdminOverrideHolder.isActive()) {
      return kbs; // 全平台破玻璃：不按组织过滤
    }
    Long orgId = com.ragforge.security.OrgContextHolder.get();
    if (orgId == null) {
      return kbs; // 未指定当前组织：兼容不过滤
    }
    // 模型 Y：严格按当前组织过滤（不再让 PUBLIC 库跨组织穿透显示）。
    return kbs.stream().filter(kb -> orgId.equals(kb.getOrgId())).toList();
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
    // 平台超管仅在破玻璃(全平台视图)下对一切可写；普通组织上下文按真实关系判定，不再处处可编辑。
    if (isAdmin && com.ragforge.security.AdminOverrideHolder.isActive()) {
      return "admin";
    }
    if (uid != null && uid.equals(kb.getOwnerUserId())) {
      return "admin";
    }
    // 组织库：本人是该组织 OWNER/ADMIN → 可管理。
    if (kb.getOrgId() != null && uid != null && orgMemberMapper.isOrgAdmin(kb.getOrgId(), uid)) {
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
    if (ctx != null && ctx.userId() != null) {
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
  public com.ragforge.common.PageResult<KnowledgeBaseVO> listVisiblePaged(
      String keyword, int page, int size) {
    int p = page < 1 ? 1 : page;
    int s = size < 1 ? 10 : Math.min(size, 100);
    List<KnowledgeBaseVO> all = listVisibleToCurrentUser();
    String kw = keyword == null ? "" : keyword.trim().toLowerCase();
    List<KnowledgeBaseVO> filtered =
        kw.isEmpty()
            ? all
            : all.stream()
                .filter(vo -> vo.getName() != null && vo.getName().toLowerCase().contains(kw))
                .toList();
    int from = Math.min((p - 1) * s, filtered.size());
    int to = Math.min(from + s, filtered.size());
    return com.ragforge.common.PageResult.of(
        filtered.size(), p, s, new java.util.ArrayList<>(filtered.subList(from, to)));
  }

  // ============ 可见性变更（P0 权限+审计 / P1 依赖预检 / P2 放开治理） ============

  @Override
  public com.ragforge.model.vo.VisibilityImpactVo visibilityImpact(Long id, String target) {
    KnowledgeBase kb = requireActiveKb(id);
    String current = normalizeVisibility(kb);
    String tgt = validateTargetVisibility(kb, target);
    return buildImpact(kb, current, tgt);
  }

  @Override
  @Transactional
  public KnowledgeBase changeVisibility(Long id, com.ragforge.model.dto.ChangeVisibilityDTO dto) {
    KnowledgeBase kb = requireActiveKb(id);
    String current = normalizeVisibility(kb);
    String target = validateTargetVisibility(kb, dto.getVisibility());
    if (current.equals(target)) {
      return kb; // 幂等，无变更
    }

    boolean narrowing = visibilityRank(target) < visibilityRank(current);
    com.ragforge.model.vo.VisibilityImpactVo impact = buildImpact(kb, current, target);

    // P1：收紧且存在阻断性依赖（他组织 key）时，需前端确认后 force 才放行。
    if (narrowing && impact.isHasBlockingDependencies() && !dto.isForce()) {
      throw new BizException(409, "KB_VISIBILITY_HAS_DEPENDENCIES");
    }
    // P2：放开到全平台需带原因（治理留痕）。
    if ("PUBLIC".equals(target) && !StringUtils.hasText(dto.getReason())) {
      throw new BizException(400, "KB_VISIBILITY_PUBLIC_REASON_REQUIRED");
    }

    Long actor =
        com.ragforge.security.RagAuthContextHolder.get() != null
            ? com.ragforge.security.RagAuthContextHolder.get().userId()
            : null;
    kb.setVisibility(target);
    kb.setUpdatedAt(LocalDateTime.now());
    knowledgeBaseMapper.updateById(kb);
    invalidateListCache();

    // P0/P2 审计：留痕 旧→新、操作人、组织、原因、受影响依赖。
    AUDIT.info(
        "kb_visibility_change kbId={} orgId={} byUser={} from={} to={} narrowing={} "
            + "crossOrgKeys={} evalDatasets={} forced={} reason={}",
        kb.getId(),
        kb.getOrgId(),
        actor,
        current,
        target,
        narrowing,
        impact.getCrossOrgApiKeys() == null ? 0 : impact.getCrossOrgApiKeys().size(),
        impact.getEvalDatasetCount(),
        dto.isForce(),
        StringUtils.hasText(dto.getReason()) ? dto.getReason() : "-");
    return kb;
  }

  /** 校验目标可见性合法性：个人组织仅 PRIVATE；团队组织 PRIVATE/ORG（已移除全域公开 PUBLIC）。 */
  private String validateTargetVisibility(KnowledgeBase kb, String target) {
    if (!StringUtils.hasText(target)) {
      throw new BizException(400, "KB_VISIBILITY_REQUIRED");
    }
    String v = target.trim().toUpperCase();
    com.ragforge.model.entity.Organization org =
        kb.getOrgId() == null ? null : organizationMapper.selectById(kb.getOrgId());
    boolean individual = org != null && "INDIVIDUAL".equals(org.getType());
    // 模型 Y：移除全域公开。个人组织仅 PRIVATE；团队组织 PRIVATE/ORG。
    Set<String> allowed = individual ? Set.of("PRIVATE") : Set.of("PRIVATE", "ORG");
    if (!allowed.contains(v)) {
      throw new BizException(400, "KB_VISIBILITY_INVALID");
    }
    return v;
  }

  private String normalizeVisibility(KnowledgeBase kb) {
    return StringUtils.hasText(kb.getVisibility())
        ? kb.getVisibility().trim().toUpperCase()
        : "PRIVATE";
  }

  /** 计算变更影响：收紧时哪些他组织 key 会断链、本库上有多少评测集。 */
  private com.ragforge.model.vo.VisibilityImpactVo buildImpact(
      KnowledgeBase kb, String current, String target) {
    com.ragforge.model.vo.VisibilityImpactVo vo = new com.ragforge.model.vo.VisibilityImpactVo();
    vo.setCurrent(current);
    vo.setTarget(target);
    boolean narrowing = visibilityRank(target) < visibilityRank(current);
    vo.setNarrowing(narrowing);
    vo.setWillOpenToPlatform("PUBLIC".equals(target));

    // 评测集（信息提示）
    Long evalCount =
        evalDatasetMapper.selectCount(
            new LambdaQueryWrapper<com.ragforge.model.entity.EvalDataset>()
                .eq(com.ragforge.model.entity.EvalDataset::getKbId, kb.getId()));
    vo.setEvalDatasetCount(evalCount == null ? 0 : evalCount.intValue());

    // 他组织 key：仅当从 PUBLIC 收紧时，他组织的引用会真正断链。
    List<com.ragforge.model.vo.VisibilityImpactVo.KeyRef> crossOrg = new java.util.ArrayList<>();
    if ("PUBLIC".equals(current) && narrowing) {
      List<com.ragforge.model.entity.ApiKey> keys =
          apiKeyMapper.selectList(
              new LambdaQueryWrapper<com.ragforge.model.entity.ApiKey>()
                  .ne(com.ragforge.model.entity.ApiKey::getOrgId, kb.getOrgId()));
      java.util.Map<Long, String> orgNames = new java.util.HashMap<>();
      for (com.ragforge.model.entity.ApiKey key : keys) {
        if (key.getOrgId() == null || !keyReferencesKb(key, kb.getId())) {
          continue;
        }
        com.ragforge.model.vo.VisibilityImpactVo.KeyRef ref =
            new com.ragforge.model.vo.VisibilityImpactVo.KeyRef();
        ref.setId(key.getId());
        ref.setKeyName(key.getKeyName());
        ref.setOrgId(key.getOrgId());
        ref.setOrgName(
            orgNames.computeIfAbsent(
                key.getOrgId(),
                oid -> {
                  com.ragforge.model.entity.Organization o = organizationMapper.selectById(oid);
                  return o == null ? null : o.getName();
                }));
        crossOrg.add(ref);
      }
    }
    vo.setCrossOrgApiKeys(crossOrg);
    vo.setHasBlockingDependencies(!crossOrg.isEmpty());
    return vo;
  }

  /** key 是否显式引用了该 KB（allowedKbIds 为空=全部，不算显式引用）。 */
  private boolean keyReferencesKb(com.ragforge.model.entity.ApiKey key, Long kbId) {
    String raw = key.getAllowedKbIds();
    if (!StringUtils.hasText(raw)) {
      return false;
    }
    try {
      List<?> ids = objectMapper.readValue(raw, List.class);
      for (Object v : ids) {
        if (v != null && Long.valueOf(String.valueOf(v).trim()).equals(kbId)) {
          return true;
        }
      }
    } catch (Exception ignore) {
      // 解析失败按未引用处理，避免误报阻断
    }
    return false;
  }

  @Override
  @Transactional
  public void delete(Long id) {
    KnowledgeBase kb = requireActiveKb(id);
    Long docCount =
        documentMapper.selectCount(
            new LambdaQueryWrapper<Document>().eq(Document::getKbId, id));
    if (docCount != null && docCount > 0) {
      // 用 409 而非默认 500：前端对 500 一律显示"服务暂时异常",会盖掉这句有意义的提示。
      throw new BizException(409, "知识库下存在 " + docCount + " 个文档，请先删除文档后再删除知识库");
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
