package com.ragforge.model.dto;

import lombok.Data;

@Data
public class UpdateKbDTO {

  private String name;
  private String description;
  private Integer chunkSize;
  private Integer chunkOverlap;
  private String status;
}
