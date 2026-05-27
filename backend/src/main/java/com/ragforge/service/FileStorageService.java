package com.ragforge.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

  /**
   * @return filePath persisted to disk (used for later deletion)
   */
  String store(MultipartFile file);

  void delete(String filePath);
}

