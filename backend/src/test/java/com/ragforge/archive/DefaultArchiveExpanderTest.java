package com.ragforge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.common.ArchiveLimits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultArchiveExpanderTest {

  private final InputStream dummy = new ByteArrayInputStream(new byte[0]);

  private ArchiveLimits limits() {
    return new ArchiveLimits(); // 默认 100:1 / 500MB / 1000 / 50MB
  }

  private DefaultArchiveExpander expander(ArchiveLimits l, FakeArchiveReader reader) {
    return new DefaultArchiveExpander(l, reader, reader);
  }

  private ExpandOutcome expandZip(ArchiveLimits l, FakeArchiveReader reader, ArchiveEntryConsumer c) {
    return expander(l, reader).expand(dummy, ArchiveFormat.ZIP, c);
  }

  private static void assertCode(Throwable t, String code) {
    assertThat(t).isInstanceOf(ArchiveException.class);
    assertThat(((ArchiveException) t).getCode()).isEqualTo(code);
  }

  @Test
  void happyPath_registersWhitelistedEntries() {
    List<ExpandedEntry> got = new ArrayList<>();
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("a.pdf", "hello"))
            .add(FakeArchiveReader.file("docs//b.md", "world"));

    ExpandOutcome outcome = expandZip(limits(), reader, got::add);

    assertThat(outcome.getTotalEntries()).isEqualTo(2);
    assertThat(outcome.getRegistered()).isEqualTo(2);
    assertThat(outcome.getSkipped()).isEmpty();
    assertThat(got).hasSize(2);
    assertThat(got.get(0).getEntryPath()).isEqualTo("a.pdf");
    assertThat(got.get(0).getFilename()).isEqualTo("a.pdf");
    assertThat(got.get(0).getContent()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    assertThat(got.get(0).getContentMd5()).isNotBlank();
    assertThat(got.get(0).getSize()).isEqualTo(5);
    // 路径规范化：docs//b.md -> docs/b.md，叶子 b.md
    assertThat(got.get(1).getEntryPath()).isEqualTo("docs/b.md");
    assertThat(got.get(1).getFilename()).isEqualTo("b.md");
  }

  @Test
  void directoryEntries_skippedNotCounted() {
    List<ExpandedEntry> got = new ArrayList<>();
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.dir("d/"))
            .add(FakeArchiveReader.file("d/a.pdf", "x"));

    ExpandOutcome outcome = expandZip(limits(), reader, got::add);

    assertThat(outcome.getTotalEntries()).isEqualTo(1);
    assertThat(outcome.getRegistered()).isEqualTo(1);
  }

  @Test
  void unsupportedType_skipped() {
    List<ExpandedEntry> got = new ArrayList<>();
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("a.exe", "x"))
            .add(FakeArchiveReader.file("b.pdf", "y"));

    ExpandOutcome outcome = expandZip(limits(), reader, got::add);

    assertThat(outcome.getTotalEntries()).isEqualTo(2);
    assertThat(outcome.getRegistered()).isEqualTo(1);
    assertThat(outcome.getSkipped()).hasSize(1);
    assertThat(outcome.getSkipped().get(0).getPath()).isEqualTo("a.exe");
    assertThat(outcome.getSkipped().get(0).getReason()).isEqualTo(SkipReason.UNSUPPORTED_TYPE.wireName());
  }

  @Test
  void nestedArchive_skipped() {
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("inner.zip", "x"))
            .add(FakeArchiveReader.file("ok.pdf", "y"));

    ExpandOutcome outcome = expandZip(limits(), reader, e -> {});

    assertThat(outcome.getRegistered()).isEqualTo(1);
    assertThat(outcome.getSkipped()).hasSize(1);
    assertThat(outcome.getSkipped().get(0).getReason()).isEqualTo(SkipReason.NESTED_ARCHIVE.wireName());
  }

  @Test
  void illegalPathAndSymlink_skipped() {
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("../evil.pdf", "x"))
            .add(FakeArchiveReader.symlink("link.pdf"))
            .add(FakeArchiveReader.file("ok.pdf", "y"));

    ExpandOutcome outcome = expandZip(limits(), reader, e -> {});

    assertThat(outcome.getRegistered()).isEqualTo(1);
    assertThat(outcome.getSkipped()).hasSize(2);
    assertThat(outcome.getSkipped())
        .allSatisfy(s -> assertThat(s.getReason()).isEqualTo(SkipReason.ILLEGAL_PATH.wireName()));
  }

  @Test
  void oversizeEntry_skippedNotFatal() {
    ArchiveLimits l = limits();
    l.setMaxEntryBytes(10);
    byte[] big = new byte[20];
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("big.pdf", big))
            .add(FakeArchiveReader.file("ok.pdf", "y"));

    ExpandOutcome outcome = expandZip(l, reader, e -> {});

    assertThat(outcome.getTotalEntries()).isEqualTo(2);
    assertThat(outcome.getRegistered()).isEqualTo(1);
    assertThat(outcome.getSkipped().get(0).getReason()).isEqualTo(SkipReason.OVERSIZE.wireName());
  }

  @Test
  void encrypted_fatal() {
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP).add(FakeArchiveReader.encrypted("secret.pdf"));
    assertThatThrownBy(() -> expandZip(limits(), reader, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.ENCRYPTED_UNSUPPORTED));
  }

  @Test
  void tooManyEntries_fatal() {
    ArchiveLimits l = limits();
    l.setMaxEntries(1);
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("a.pdf", "x"))
            .add(FakeArchiveReader.file("b.pdf", "y"));
    assertThatThrownBy(() -> expandZip(l, reader, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.TOO_MANY_ENTRIES));
  }

  @Test
  void totalSizeExceeded_fatal() {
    ArchiveLimits l = limits();
    l.setMaxTotalUncompressedBytes(15);
    l.setMaxEntryBytes(1000);
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("a.pdf", new byte[10]))
            .add(FakeArchiveReader.file("b.pdf", new byte[10]));
    assertThatThrownBy(() -> expandZip(l, reader, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.TOTAL_SIZE_EXCEEDED));
  }

  @Test
  void compressionRatio_fatal() {
    ArchiveLimits l = limits();
    l.setMaxCompressionRatio(100);
    l.setMaxEntryBytes(1_000_000);
    l.setMaxTotalUncompressedBytes(1_000_000);
    // 2000 解压 / 10 压缩 = 200:1 > 100:1
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.withCompressed("bomb.pdf", new byte[2000], 10));
    assertThatThrownBy(() -> expandZip(l, reader, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.SUSPICIOUS_RATIO));
  }

  @Test
  void emptyArchive_fatal() {
    assertThatThrownBy(() -> expandZip(limits(), new FakeArchiveReader(ArchiveFormat.ZIP), e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.EMPTY));
    // 仅目录 entry 亦视为空
    FakeArchiveReader onlyDirs = new FakeArchiveReader(ArchiveFormat.ZIP).add(FakeArchiveReader.dir("d/"));
    assertThatThrownBy(() -> expandZip(limits(), onlyDirs, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.EMPTY));
  }

  @Test
  void corruptedStream_mappedToCorrupted() {
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP).failWith(new IOException("truncated"));
    assertThatThrownBy(() -> expandZip(limits(), reader, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.CORRUPTED));
  }

  @Test
  void consumerFailure_recordedAsRegisterFailedSkip() {
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP).add(FakeArchiveReader.file("a.pdf", "x"));
    ArchiveEntryConsumer throwing =
        e -> {
          throw new IllegalStateException("oss down");
        };
    ExpandOutcome outcome = expandZip(limits(), reader, throwing);
    assertThat(outcome.getTotalEntries()).isEqualTo(1);
    assertThat(outcome.getRegistered()).isZero();
    assertThat(outcome.getSkipped().get(0).getReason()).isEqualTo(SkipReason.REGISTER_FAILED.wireName());
  }

  @Test
  void partialSuccess_mixedBag() {
    ArchiveLimits l = limits();
    l.setMaxEntryBytes(10);
    FakeArchiveReader reader =
        new FakeArchiveReader(ArchiveFormat.ZIP)
            .add(FakeArchiveReader.file("good.pdf", "y"))
            .add(FakeArchiveReader.file("bad.exe", "y"))
            .add(FakeArchiveReader.file("inner.zip", "y"))
            .add(FakeArchiveReader.file("../evil.pdf", "y"))
            .add(FakeArchiveReader.file("big.pdf", new byte[20]));

    ExpandOutcome outcome = expandZip(l, reader, e -> {});

    assertThat(outcome.getTotalEntries()).isEqualTo(5);
    assertThat(outcome.getRegistered()).isEqualTo(1);
    assertThat(outcome.getSkipped()).hasSize(4);
  }

  @Test
  void springContextCanInstantiateBean() {
    // 复现流水线故障：DefaultArchiveExpander 有多构造器，须 @Autowired 标注生产构造器，
    // 否则 Spring 找不到唯一/默认构造器，容器启动即失败。
    try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      ctx.register(com.ragforge.common.ArchiveLimits.class, DefaultArchiveExpander.class);
      ctx.refresh();
      assertThat(ctx.getBean(DefaultArchiveExpander.class)).isNotNull();
    }
  }

  @Test
  void unsupportedFormat_fatal() {
    FakeArchiveReader reader = new FakeArchiveReader(ArchiveFormat.ZIP);
    assertThatThrownBy(
            () -> expander(limits(), reader).expand(dummy, ArchiveFormat.SEVEN_Z, e -> {}))
        .satisfies(t -> assertCode(t, ArchiveErrorCodes.UNSUPPORTED_FORMAT));
  }
}
