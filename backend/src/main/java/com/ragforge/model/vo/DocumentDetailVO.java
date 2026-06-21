package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class DocumentDetailVO extends DocumentVO {

  private List<DocumentChunkVO> chunks;
  private String cleanReportJson;
  private Long cleanProfileId;
}
