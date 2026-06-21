package com.ragforge.pipeline.image;

public interface OcrClient {

  OcrResult recognize(byte[] imageBytes, String contentType, String filename);
}
