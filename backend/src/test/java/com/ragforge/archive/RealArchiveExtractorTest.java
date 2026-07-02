package com.ragforge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.common.ArchiveLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

/** 用真实 zip / tar.gz 字节走完整 DefaultArchiveExpander，覆盖 ZipExtractor / TarGzExtractor。 */
class RealArchiveExtractorTest {

  private final DefaultArchiveExpander expander = new DefaultArchiveExpander(new ArchiveLimits());

  private static byte[] buildZip() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      writeZip(zos, "a.pdf", "hello");
      zos.putNextEntry(new ZipEntry("nested/")); // 目录
      zos.closeEntry();
      writeZip(zos, "nested/b.md", "world");
      writeZip(zos, "c.exe", "binary"); // 非白名单
    }
    return bos.toByteArray();
  }

  private static void writeZip(ZipOutputStream zos, String name, String content) throws Exception {
    zos.putNextEntry(new ZipEntry(name));
    zos.write(content.getBytes(StandardCharsets.UTF_8));
    zos.closeEntry();
  }

  private static byte[] buildTarGz() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(bos);
        TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
      writeTar(tos, "a.pdf", "hello");
      writeTar(tos, "docs/b.md", "world");
      writeTar(tos, "c.exe", "binary");
    }
    return bos.toByteArray();
  }

  private static void writeTar(TarArchiveOutputStream tos, String name, String content)
      throws Exception {
    byte[] data = content.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(data.length);
    tos.putArchiveEntry(entry);
    tos.write(data);
    tos.closeArchiveEntry();
  }

  @Test
  void realZip_expandsWhitelistedSkipsRest() throws Exception {
    List<ExpandedEntry> got = new ArrayList<>();
    ExpandOutcome outcome =
        expander.expand(new ByteArrayInputStream(buildZip()), ArchiveFormat.ZIP, got::add);

    assertThat(outcome.getTotalEntries()).isEqualTo(3); // 目录不计数
    assertThat(outcome.getRegistered()).isEqualTo(2);
    assertThat(outcome.getSkipped()).hasSize(1);
    assertThat(outcome.getSkipped().get(0).getReason())
        .isEqualTo(SkipReason.UNSUPPORTED_TYPE.wireName());
    assertThat(got).extracting(ExpandedEntry::getEntryPath).containsExactly("a.pdf", "nested/b.md");
    assertThat(new String(got.get(0).getContent(), StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(got.get(0).getContentMd5()).isNotBlank();
  }

  @Test
  void realTarGz_expandsWhitelistedSkipsRest() throws Exception {
    List<ExpandedEntry> got = new ArrayList<>();
    ExpandOutcome outcome =
        expander.expand(new ByteArrayInputStream(buildTarGz()), ArchiveFormat.TAR_GZ, got::add);

    assertThat(outcome.getTotalEntries()).isEqualTo(3);
    assertThat(outcome.getRegistered()).isEqualTo(2);
    assertThat(got).extracting(ExpandedEntry::getEntryPath).containsExactly("a.pdf", "docs/b.md");
    assertThat(new String(got.get(1).getContent(), StandardCharsets.UTF_8)).isEqualTo("world");
  }

  @Test
  void realTarGz_compressionBomb_trippedByArchiveRatioGuard() throws Exception {
    // 高压缩比 tar.gz（大量零字节）——gzip 无逐 entry 压缩前大小，靠"整包比值"护栏拦截
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(bos);
        TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
      byte[] zeros = new byte[512 * 1024]; // 512KB 零 → 压缩后 ~KB，比值远超阈值
      TarArchiveEntry entry = new TarArchiveEntry("a.pdf");
      entry.setSize(zeros.length);
      tos.putArchiveEntry(entry);
      tos.write(zeros);
      tos.closeArchiveEntry();
    }
    com.ragforge.common.ArchiveLimits limits = new com.ragforge.common.ArchiveLimits();
    limits.setMaxCompressionRatio(10); // 收紧比值便于确定性触发
    DefaultArchiveExpander tight = new DefaultArchiveExpander(limits);

    assertThatThrownBy(
            () -> tight.expand(new ByteArrayInputStream(bos.toByteArray()), ArchiveFormat.TAR_GZ, e -> {}))
        .isInstanceOf(ArchiveException.class)
        .satisfies(
            t ->
                assertThat(((ArchiveException) t).getCode())
                    .isEqualTo(ArchiveErrorCodes.SUSPICIOUS_RATIO));
  }

  @Test
  void corruptedGzip_mappedToArchiveException() {
    byte[] garbage = {(byte) 0x1F, (byte) 0x8B, 1, 2, 3, 4, 5, 6};
    assertThatThrownBy(
            () -> expander.expand(new ByteArrayInputStream(garbage), ArchiveFormat.TAR_GZ, e -> {}))
        .isInstanceOf(ArchiveException.class);
  }
}
