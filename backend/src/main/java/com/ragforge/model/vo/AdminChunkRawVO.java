package com.ragforge.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminChunkRawVO {

  private Long chunkId;
  private Integer vlVectorDim;
  private String modality;
  private String chunkMetadataJson;
}
