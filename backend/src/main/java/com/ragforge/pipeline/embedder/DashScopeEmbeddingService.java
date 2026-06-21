package com.ragforge.pipeline.embedder;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashScopeEmbeddingService implements EmbeddingService {

  private final VlEmbeddingClient vlEmbeddingClient;

  @Override
  public float[] embed(String text) {
    List<float[]> vectors = embedBatch(List.of(text));
    return vectors.get(0);
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      return List.of();
    }
    List<EmbeddingInput> inputs = new ArrayList<>(texts.size());
    for (String text : texts) {
      inputs.add(EmbeddingInput.text(text));
    }
    return vlEmbeddingClient.embed(inputs);
  }
}
