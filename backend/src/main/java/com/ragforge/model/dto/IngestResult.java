package com.ragforge.model.dto;

import lombok.Data;

@Data
public class IngestResult {
  private Long documentId;
  private Status status;
  private String message;

  public enum Status {
    CREATED,
    SKIPPED,
    REPLACED
  }
}
