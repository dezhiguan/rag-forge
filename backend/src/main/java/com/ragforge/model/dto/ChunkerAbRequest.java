package com.ragforge.model.dto;

import com.ragforge.pipeline.chunker.ChunkParams;
import java.util.List;
import lombok.Data;

@Data
public class ChunkerAbRequest {

  private Long evalDatasetId;
  private List<String> strategies;
  private ChunkParams params;
}
