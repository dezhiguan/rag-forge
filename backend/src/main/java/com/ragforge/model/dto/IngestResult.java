package com.ragforge.model.dto;

import lombok.Data;

@Data
public class IngestResult {
  private Long documentId;
  private Status status;
  private String message;

  public static IngestResult created(Long documentId) {
    return of(documentId, Status.CREATED, null);
  }

  public static IngestResult skipped(Long documentId) {
    return of(documentId, Status.SKIPPED, null);
  }

  public static IngestResult replaced(Long documentId) {
    return of(documentId, Status.REPLACED, null);
  }

  public static IngestResult of(Long documentId, Status status, String message) {
    IngestResult result = new IngestResult();
    result.setDocumentId(documentId);
    result.setStatus(status);
    result.setMessage(message);
    return result;
  }

  public enum Status {
    CREATED,
    SKIPPED,
    REPLACED
  }
}
