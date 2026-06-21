package com.ragforge.pipeline.embedder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashScopeEmbeddingServiceTest {

  @Mock private VlEmbeddingClient vlEmbeddingClient;

  @Test
  void embed_delegatesToVlEmbeddingClient() {
    when(vlEmbeddingClient.embed(any())).thenReturn(List.of(new float[2560]));
    DashScopeEmbeddingService service = new DashScopeEmbeddingService(vlEmbeddingClient);

    float[] vector = service.embed("后端开发");

    assertThat(vector).hasSize(2560);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EmbeddingInput>> captor = ArgumentCaptor.forClass(List.class);
    verify(vlEmbeddingClient).embed(captor.capture());
    assertThat(captor.getValue()).hasSize(1);
    assertThat(captor.getValue().get(0).getText()).isEqualTo("后端开发");
  }

  @Test
  void embedBatch_emptyReturnsEmpty() {
    DashScopeEmbeddingService service = new DashScopeEmbeddingService(vlEmbeddingClient);

    assertThat(service.embedBatch(List.of())).isEmpty();
  }
}
