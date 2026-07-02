package com.ragforge.archive;

import org.springframework.stereotype.Component;

/**
 * 依文件头 magic number 判定压缩格式，<b>不依赖扩展名</b>（防改名绕过）。用于上传分流：识别 zip/tar.gz
 * 走展开分支，识别 7z/rar 走拒绝分支，其余按普通文件处理。
 */
@Component
public class ArchiveFormatDetector {

  /** 判定所需的最少文件头字节数。 */
  public static final int HEADER_BYTES = 8;

  /**
   * 依文件头字节判定格式。header 可短于 {@link #HEADER_BYTES}（按实际长度尽力判定）。
   *
   * @param header 文件头字节（至少前若干字节）
   * @return 识别到的格式；非压缩包返回 {@link ArchiveFormat#UNKNOWN}
   */
  public ArchiveFormat detect(byte[] header) {
    if (header == null || header.length < 2) {
      return ArchiveFormat.UNKNOWN;
    }
    // ZIP: "PK" 0x03 0x04（本地文件头）/ 0x05 0x06（空包）/ 0x07 0x08（分卷）
    if (header.length >= 4
        && (header[0] & 0xFF) == 0x50
        && (header[1] & 0xFF) == 0x4B) {
      int b2 = header[2] & 0xFF;
      int b3 = header[3] & 0xFF;
      if ((b2 == 0x03 && b3 == 0x04) || (b2 == 0x05 && b3 == 0x06) || (b2 == 0x07 && b3 == 0x08)) {
        return ArchiveFormat.ZIP;
      }
    }
    // GZIP: 0x1F 0x8B（tar.gz 的外层压缩；单文件 .gz 亦同，解 tar 阶段会失败为 CORRUPTED）
    if ((header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) {
      return ArchiveFormat.TAR_GZ;
    }
    // 7z: 37 7A BC AF 27 1C
    if (header.length >= 6
        && (header[0] & 0xFF) == 0x37
        && (header[1] & 0xFF) == 0x7A
        && (header[2] & 0xFF) == 0xBC
        && (header[3] & 0xFF) == 0xAF
        && (header[4] & 0xFF) == 0x27
        && (header[5] & 0xFF) == 0x1C) {
      return ArchiveFormat.SEVEN_Z;
    }
    // RAR: "Rar!" 0x1A 0x07（RAR4=00 / RAR5=01 00）
    if (header.length >= 6
        && (header[0] & 0xFF) == 0x52
        && (header[1] & 0xFF) == 0x61
        && (header[2] & 0xFF) == 0x72
        && (header[3] & 0xFF) == 0x21
        && (header[4] & 0xFF) == 0x1A
        && (header[5] & 0xFF) == 0x07) {
      return ArchiveFormat.RAR;
    }
    return ArchiveFormat.UNKNOWN;
  }
}
