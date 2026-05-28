package com.ragforge.model.vo;

import lombok.Data;

@Data
public class DashboardActivityVO {
  private String time;
  private String type;
  private String message;
  private Long docId;
  private Boolean retryable;
}

