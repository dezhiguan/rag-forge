package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.KnowledgeBaseService;
import java.time.LocalDateTime;
import java.util.List;
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

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
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
    kb.setTenantId(auth == null || !StringUtils.hasText(auth.tenantId()) ? "tn_default" : auth.tenantId());
    kb.setOwnerUserId(auth == null || auth.userId() == null ? 0L : auth.userId());
    kb.setVisibility("PRIVATE");
    kb.setKbType("GENERAL");
    kb.setImageProcessingMode("OFF");
    LocalDateTime now = LocalDateTime.now();
    kb.setCreatedAt(now);
    kb.setUpdatedAt(now);
    knowledgeBaseMapper.insert(kb);
    invalidateListCache();
    return kb;
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

  private List<KnowledgeBaseVO> loadListAll() {
    List<KnowledgeBase> list =
        knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .ne(KnowledgeBase::getStatus, STATUS_DELETED)
                .orderByDesc(KnowledgeBase::getCreatedAt));
    return list.stream().map(KnowledgeBaseVO::fromEntity).toList();
  }

  @Override
  public KnowledgeBaseVO getById(Long id) {
    KnowledgeBase kb = requireActiveKb(id);
    return KnowledgeBaseVO.fromEntity(kb);
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

  private record ListCache(List<KnowledgeBaseVO> value, long expiresAtMs) {}
}
