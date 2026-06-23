package com.ragforge.judge.vo;

import lombok.Data;

@Data
public class ReplayResultVo {

  private int requested;
  private int success;
  private int failed;
  private String message;
  private Long datasetId;
  private Long startedAt;
}
