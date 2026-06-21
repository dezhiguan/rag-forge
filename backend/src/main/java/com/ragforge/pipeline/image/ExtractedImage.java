package com.ragforge.pipeline.image;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedImage {

  private byte[] bytes;
  private String contentType;
  private Integer pageNo;
  private Integer figureIndex;
  private String surroundingText;
  private String captionText;
}
