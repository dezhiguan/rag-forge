package com.ragforge.model.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class LlmGenerateRequest {

  private List<Map<String, String>> messages;
  private String model;
  private Double temperature;
}
