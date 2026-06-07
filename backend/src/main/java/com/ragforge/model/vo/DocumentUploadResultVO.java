package com.ragforge.model.vo;

import lombok.Data;

@Data
public class DocumentUploadResultVO {

  private boolean exists;
  private DocumentVO existingDocument;
  private DocumentVO document;
  private Long documentId;
  private String status;
  private String message;
  private Long docId;
  private String filename;
  private boolean skipped = false;
}

