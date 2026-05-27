package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

@Data
public class CreateEvalQuestionDTO {

  @NotBlank(message = "问题不能为空")
  private String question;

  private List<Long> expectedChunkIds;
}
