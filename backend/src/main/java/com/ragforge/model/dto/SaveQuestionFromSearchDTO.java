package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

@Data
public class SaveQuestionFromSearchDTO {

  @NotBlank(message = "问题不能为空")
  private String question;

  private String strategy;

  private List<Long> selectedChunkIds;
}
