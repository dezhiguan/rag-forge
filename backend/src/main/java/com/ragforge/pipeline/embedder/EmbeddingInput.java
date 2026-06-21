package com.ragforge.pipeline.embedder;

import lombok.Data;

@Data
public class EmbeddingInput {

  private String text;
  private byte[] imageBytes;
  private String imageContentType;

  public static EmbeddingInput text(String text) {
    EmbeddingInput input = new EmbeddingInput();
    input.setText(text == null ? "" : text);
    return input;
  }

  public static EmbeddingInput image(byte[] imageBytes, String imageContentType) {
    EmbeddingInput input = new EmbeddingInput();
    input.setImageBytes(imageBytes);
    input.setImageContentType(imageContentType);
    return input;
  }

  public boolean isImage() {
    return imageBytes != null && imageBytes.length > 0;
  }
}
