package com.ragforge.pipeline.image;

public interface ImageEmbeddingClient {

  float[] embedImage(byte[] imageBytes, String contentType);

  float[] embedText(String query);
}
