package com.ragforge.service;

import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import java.util.List;

public interface KnowledgeBaseService {

  KnowledgeBase create(CreateKbDTO dto);

  List<KnowledgeBaseVO> listAll();

  KnowledgeBaseVO getById(Long id);

  KnowledgeBase update(Long id, UpdateKbDTO dto);

  void delete(Long id);
}
