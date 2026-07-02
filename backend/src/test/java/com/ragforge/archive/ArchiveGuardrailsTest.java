package com.ragforge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchiveGuardrailsTest {

  @Test
  void normalizePath_simpleRelative_ok() {
    assertThat(ArchiveGuardrails.normalizePath("docs/2024/a.pdf")).isEqualTo("docs/2024/a.pdf");
  }

  @Test
  void normalizePath_backslashesNormalized() {
    assertThat(ArchiveGuardrails.normalizePath("docs\\a.pdf")).isEqualTo("docs/a.pdf");
  }

  @Test
  void normalizePath_dropsDotAndEmptySegments() {
    assertThat(ArchiveGuardrails.normalizePath("./docs//a.pdf")).isEqualTo("docs/a.pdf");
  }

  @Test
  void normalizePath_parentTraversal_rejected() {
    assertThat(ArchiveGuardrails.normalizePath("../evil.pdf")).isNull();
    assertThat(ArchiveGuardrails.normalizePath("a/b/../../../../etc/x")).isNull();
    assertThat(ArchiveGuardrails.normalizePath("docs/../../x")).isNull();
  }

  @Test
  void normalizePath_absolute_rejected() {
    assertThat(ArchiveGuardrails.normalizePath("/etc/passwd")).isNull();
    assertThat(ArchiveGuardrails.normalizePath("C:/windows/x")).isNull();
    assertThat(ArchiveGuardrails.normalizePath("D:evil")).isNull();
  }

  @Test
  void normalizePath_backslashTraversal_rejected() {
    assertThat(ArchiveGuardrails.normalizePath("..\\..\\evil")).isNull();
  }

  @Test
  void normalizePath_nullBlankOrNulByte_rejected() {
    assertThat(ArchiveGuardrails.normalizePath(null)).isNull();
    assertThat(ArchiveGuardrails.normalizePath("   ")).isNull();
    assertThat(ArchiveGuardrails.normalizePath("a\0b")).isNull();
    assertThat(ArchiveGuardrails.normalizePath("///")).isNull();
  }

  @Test
  void isNestedArchive_detectsArchiveExtensions() {
    assertThat(ArchiveGuardrails.isNestedArchive("inner.zip")).isTrue();
    assertThat(ArchiveGuardrails.isNestedArchive("inner.tar.gz")).isTrue();
    assertThat(ArchiveGuardrails.isNestedArchive("inner.7z")).isTrue();
    assertThat(ArchiveGuardrails.isNestedArchive("inner.RAR")).isTrue();
    assertThat(ArchiveGuardrails.isNestedArchive("a.pdf")).isFalse();
    assertThat(ArchiveGuardrails.isNestedArchive("noext")).isFalse();
    assertThat(ArchiveGuardrails.isNestedArchive(null)).isFalse();
  }

  @Test
  void leafName_returnsLastSegment() {
    assertThat(ArchiveGuardrails.leafName("docs/2024/a.pdf")).isEqualTo("a.pdf");
    assertThat(ArchiveGuardrails.leafName("a.pdf")).isEqualTo("a.pdf");
    assertThat(ArchiveGuardrails.leafName(null)).isNull();
  }
}
