package com.ragforge.pipeline.image;

import java.nio.file.Path;
import java.util.List;

public interface EmbeddedImageExtractor {

  boolean supports(String contentType);

  List<ExtractedImage> extract(Path filePath, String contentType);
}
