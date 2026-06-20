package com.ragforge.model.dto;

import lombok.Data;

@Data
public class Identity {
  private String externalId;
  private String sourceUrl;
  private String contentMd5;
}
