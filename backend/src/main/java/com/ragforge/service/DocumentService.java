package com.ragforge.service;

import com.ragforge.common.PageResult;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

  DocumentUploadResultVO upload(Long kbId, MultipartFile file);

  DocumentVO replaceDocument(Long kbId, MultipartFile file, Long existingDocId);

  PageResult<DocumentVO> listByKb(Long kbId, int page, int size);

  DocumentDetailVO getById(Long id);

  DocumentStatusVO getStatus(Long id);

  ResponseEntity<Resource> download(Long id);

  void delete(Long id);
}

