package com.ragforge.pipeline.chunker;

import java.util.List;
import lombok.Data;

@Data
public class ChunkParams {
  private int chunkSize = 500;
  private int overlap = 50;
  private List<String> separators = List.of("\n\n", "\n", "。", ",");
  private Integer maxHeadingLevel = 3;
  private Double simThreshold = 0.65;
  private TablePolicy tablePolicy = TablePolicy.WHOLE;
}
