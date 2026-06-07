package com.ragforge.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

  /**
   * @return filePath persisted to disk (used for later deletion)
   */
  String store(MultipartFile file);

  /** @return filePath persisted to disk */
  String storeBytes(byte[] content, String filename);

  void delete(String filePath);
}

