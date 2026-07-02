package com.ragforge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchiveFormatDetectorTest {

  private final ArchiveFormatDetector detector = new ArchiveFormatDetector();

  @Test
  void detectsZipLocalHeader() {
    assertThat(detector.detect(new byte[] {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0}))
        .isEqualTo(ArchiveFormat.ZIP);
  }

  @Test
  void detectsEmptyAndSpannedZip() {
    assertThat(detector.detect(new byte[] {0x50, 0x4B, 0x05, 0x06})).isEqualTo(ArchiveFormat.ZIP);
    assertThat(detector.detect(new byte[] {0x50, 0x4B, 0x07, 0x08})).isEqualTo(ArchiveFormat.ZIP);
  }

  @Test
  void detectsGzipAsTarGz() {
    assertThat(detector.detect(new byte[] {(byte) 0x1F, (byte) 0x8B, 0x08, 0}))
        .isEqualTo(ArchiveFormat.TAR_GZ);
  }

  @Test
  void detectsSevenZ() {
    assertThat(
            detector.detect(
                new byte[] {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 0}))
        .isEqualTo(ArchiveFormat.SEVEN_Z);
  }

  @Test
  void detectsRar() {
    assertThat(detector.detect(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00}))
        .isEqualTo(ArchiveFormat.RAR);
  }

  @Test
  void unknownForPlainOrShortOrNull() {
    assertThat(detector.detect("%PDF-1.7".getBytes())).isEqualTo(ArchiveFormat.UNKNOWN);
    assertThat(detector.detect(new byte[] {0x50})).isEqualTo(ArchiveFormat.UNKNOWN);
    assertThat(detector.detect(null)).isEqualTo(ArchiveFormat.UNKNOWN);
    // PK 但非有效子签名
    assertThat(detector.detect(new byte[] {0x50, 0x4B, 0x01, 0x02})).isEqualTo(ArchiveFormat.UNKNOWN);
  }

  @Test
  void formatTokensAndFlags() {
    assertThat(ArchiveFormat.ZIP.token()).isEqualTo("zip");
    assertThat(ArchiveFormat.TAR_GZ.token()).isEqualTo("tar.gz");
    assertThat(ArchiveFormat.ZIP.isSupported()).isTrue();
    assertThat(ArchiveFormat.SEVEN_Z.isSupported()).isFalse();
    assertThat(ArchiveFormat.SEVEN_Z.isArchive()).isTrue();
    assertThat(ArchiveFormat.UNKNOWN.isArchive()).isFalse();
    assertThat(ArchiveFormat.UNKNOWN.token()).isNull();
  }
}
