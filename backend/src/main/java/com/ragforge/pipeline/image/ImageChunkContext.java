package com.ragforge.pipeline.image;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageChunkContext {

  private Integer pageNo;
  private Integer figureIndex;
  private String surroundingText;
  private String captionText;

  public static ImageChunkContext of(ExtractedImage image) {
    if (image == null) {
      return new ImageChunkContext();
    }
    return new ImageChunkContext(
        image.getPageNo(),
        image.getFigureIndex(),
        image.getSurroundingText(),
        image.getCaptionText());
  }
}
