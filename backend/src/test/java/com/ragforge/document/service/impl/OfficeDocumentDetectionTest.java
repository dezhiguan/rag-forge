package com.ragforge.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 校验 OOXML/Office 文档识别——它们是 zip 结构，必须排除在压缩包展开之外。 */
class OfficeDocumentDetectionTest {

  @Test
  void recognizesOoxmlAndLegacyOfficeDocuments() {
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("report.docx")).isTrue();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("sheet.xlsx")).isTrue();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("slides.pptx")).isTrue();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("old.doc")).isTrue();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("OLD.XLS")).isTrue(); // 大小写不敏感
  }

  @Test
  void doesNotMisclassifyRealArchivesOrOtherFiles() {
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("data.zip")).isFalse();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("bundle.tar.gz")).isFalse();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("doc.pdf")).isFalse();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("note.txt")).isFalse();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("image.png")).isFalse();
  }

  @Test
  void handlesEdgeCases() {
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument(null)).isFalse();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("noext")).isFalse();
    assertThat(DocumentUploadApplicationServiceImpl.isOfficeDocument("trailingdot.")).isFalse();
  }
}
