package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ChunkerProfile {

  private String defaultStrategy = "MARKDOWN_HEADING";
  // STRUCTURED_HEADING 在 RECURSIVE 之前:识别中文【】/序号/章节等结构标记按小节切;
  // 无结构标记时它返回空,自动降级到 RECURSIVE。
  private List<String> fallbackChain =
      new ArrayList<>(List.of("STRUCTURED_HEADING", "RECURSIVE", "FIXED_WINDOW"));
  private ChunkParams params = new ChunkParams();
}
