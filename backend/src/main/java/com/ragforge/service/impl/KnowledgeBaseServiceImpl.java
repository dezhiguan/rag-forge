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

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;

  @Override
  @Transactional
  public KnowledgeBase create(CreateKbDTO dto) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setName(dto.getName().trim());
    kb.setDescription(dto.getDescription());
    kb.setEmbeddingModel("text-embedding-v4");
    kb.setChunkSize(dto.getChunkSize() != null ? dto.getChunkSize() : 512);
    kb.setChunkOverlap(dto.getChunkOverlap() != null ? dto.getChunkOverlap() : 64);
    kb.setDocCount(0);
    kb.setChunkCount(0);
    kb.setStatus(STATUS_ACTIVE);
    LocalDateTime now = LocalDateTime.now();
    kb.setCreatedAt(now);
    kb.setUpdatedAt(now);
    knowledgeBaseMapper.insert(kb);
    return kb;
  }

  @Override
  public List<KnowledgeBaseVO> listAll() {
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
  }

  private KnowledgeBase requireActiveKb(Long id) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
    if (kb == null || STATUS_DELETED.equals(kb.getStatus())) {
      throw new BizException(404, "知识库不存在");
    }
    return kb;
  }
}
