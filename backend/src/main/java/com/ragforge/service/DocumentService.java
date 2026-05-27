package com.ragforge.service;

import com.ragforge.common.PageResult;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

  DocumentVO upload(Long kbId, MultipartFile file);

  PageResult<DocumentVO> listByKb(Long kbId, int page, int size);

  DocumentDetailVO getById(Long id);

  DocumentStatusVO getStatus(Long id);

  void delete(Long id);
}

