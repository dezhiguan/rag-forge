package com.ragforge.service.impl;

import com.ragforge.service.FileStorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {

  @Value("${app.file.storage-path:${ragforge.files.upload-path:./data/files}}")
  private String storagePath;

  @Override
  public String store(MultipartFile file) {
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isBlank()) {
      throw new IllegalArgumentException("file name is required");
    }

    String extension = getExtension(originalFilename);
    String fileName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);

    try {
      Path dir = Paths.get(storagePath).toAbsolutePath().normalize();
      Files.createDirectories(dir);
      Path target = dir.resolve(fileName);
      file.transferTo(target);
      return target.toString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to store file", e);
    }
  }

  @Override
  public String storeBytes(byte[] content, String filename) {
    if (filename == null || filename.isBlank()) {
      throw new IllegalArgumentException("file name is required");
    }
    if (content == null) {
      throw new IllegalArgumentException("content is required");
    }

    String extension = getExtension(filename);
    String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);

    try {
      Path dir = Paths.get(storagePath).toAbsolutePath().normalize();
      Files.createDirectories(dir);
      Path target = dir.resolve(storedName);
      Files.write(target, content);
      return target.toString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to store file", e);
    }
  }

  @Override
  public void delete(String filePath) {
    if (filePath == null || filePath.isBlank()) {
      return;
    }
    try {
      Files.deleteIfExists(Paths.get(filePath));
    } catch (IOException e) {
      // 删除失败不影响业务主流程（例如文件已被清理），只记录异常
      throw new RuntimeException("Failed to delete file: " + filePath, e);
    }
  }

  private String getExtension(String filename) {
    int idx = filename.lastIndexOf('.');
    if (idx < 0 || idx == filename.length() - 1) {
      return "";
    }
    return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
  }
}

