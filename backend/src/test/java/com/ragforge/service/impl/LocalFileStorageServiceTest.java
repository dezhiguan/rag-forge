package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LocalFileStorageServiceTest {

  @TempDir Path tempDir;

  private LocalFileStorageService storageService;

  @BeforeEach
  void setUp() {
    storageService = new LocalFileStorageService();
    ReflectionTestUtils.setField(storageService, "storagePath", tempDir.toString());
  }

  @Test
  void store_writesUploadedFile() {
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", "bytes".getBytes());

    String path = storageService.store(file);

    assertThat(Files.exists(Path.of(path))).isTrue();
    assertThat(path).endsWith(".pdf");
  }

  @Test
  void storeBytes_writesContent() {
    String path = storageService.storeBytes("hello".getBytes(), "note.md");

    assertThat(Files.exists(Path.of(path))).isTrue();
    assertThat(path).endsWith(".md");
  }

  @Test
  void store_rejectsBlankFilename() {
    MockMultipartFile file = new MockMultipartFile("file", "", "text/plain", "x".getBytes());

    assertThatThrownBy(() -> storageService.store(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file name");
  }

  @Test
  void delete_removesExistingFile() throws Exception {
    Path file = tempDir.resolve("to-delete.txt");
    Files.writeString(file, "x");

    storageService.delete(file.toString());

    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void delete_blankPath_isNoOp() {
    storageService.delete("  ");
  }
}
