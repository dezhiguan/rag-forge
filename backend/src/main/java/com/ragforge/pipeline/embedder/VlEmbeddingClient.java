package com.ragforge.pipeline.embedder;

import java.util.List;

public interface VlEmbeddingClient {

  List<float[]> embed(List<EmbeddingInput> inputs);
}
