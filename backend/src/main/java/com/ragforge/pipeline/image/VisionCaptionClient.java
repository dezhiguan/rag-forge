package com.ragforge.pipeline.image;

public interface VisionCaptionClient {

  String describe(byte[] imageBytes, String contentType, String filename);
}
