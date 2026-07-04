package com.ragforge.service;

import com.ragforge.common.PageResult;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

  DocumentUploadResultVO upload(Long kbId, MultipartFile file);

  DocumentUploadResultVO upload(Long kbId, MultipartFile file, boolean overwrite);

  DocumentVO replaceDocument(Long kbId, MultipartFile file, Long existingDocId);

  PageResult<DocumentVO> listByKb(Long kbId, int page, int size, String keyword);

  /**
   * @param flatten true=文档列表层(平铺子文档+独立文档，排除容器)；false=知识库列表层(容器+独立文档)。
   */
  PageResult<DocumentVO> listByKb(Long kbId, int page, int size, String keyword, boolean flatten);

  DocumentDetailVO getById(Long id);

  java.util.List<DocumentVO> listChildren(Long containerId);

  PageResult<DocumentChunkVO> listChunks(Long id, int page, int size, String keyword);

  DocumentStatusVO getStatus(Long id);

  ResponseEntity<Resource> download(Long id);

  void delete(Long id);

  void reprocess(Long id);

}
