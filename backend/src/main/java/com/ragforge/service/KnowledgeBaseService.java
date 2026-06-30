package com.ragforge.service;

import com.ragforge.model.dto.ChangeVisibilityDTO;
import com.ragforge.model.dto.CreateKbDTO;
import com.ragforge.model.dto.UpdateKbDTO;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.KnowledgeBaseVO;
import com.ragforge.model.vo.VisibilityImpactVo;
import java.util.List;

public interface KnowledgeBaseService {

  KnowledgeBase create(CreateKbDTO dto);

  List<KnowledgeBaseVO> listAll();

  /** 按当前登录主体做行级过滤的列表，每行附 myPermission。 */
  List<KnowledgeBaseVO> listVisibleToCurrentUser();

  /** 同上但后端分页 + 按名称模糊搜索（keyword 为空则全量分页）。 */
  com.ragforge.common.PageResult<KnowledgeBaseVO> listVisiblePaged(
      String keyword, int page, int size);

  KnowledgeBaseVO getById(Long id);

  KnowledgeBase update(Long id, UpdateKbDTO dto);

  void delete(Long id);

  /** P1：评估可见性变更影响（收紧前预检跨组织依赖）。 */
  VisibilityImpactVo visibilityImpact(Long id, String target);

  /** P0/P1/P2：修改可见性（权限+依赖预检+审计）。 */
  KnowledgeBase changeVisibility(Long id, ChangeVisibilityDTO dto);
}
