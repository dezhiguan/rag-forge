package com.ragforge.service;

import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import java.util.List;

public interface KnowledgeBaseService {

  KnowledgeBase create(CreateKbDTO dto);

  List<KnowledgeBaseVO> listAll();

  /** 按当前登录主体做行级过滤的列表，每行附 myPermission。 */
  List<KnowledgeBaseVO> listVisibleToCurrentUser();

  KnowledgeBaseVO getById(Long id);

  KnowledgeBase update(Long id, UpdateKbDTO dto);

  void delete(Long id);
}
