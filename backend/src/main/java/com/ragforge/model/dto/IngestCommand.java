package com.ragforge.model.dto;

import java.util.Map;
import lombok.Data;

@Data
public class IngestCommand {
  private Long kbId;
  private String storageBucket;
  private String storageKey;
  private String filename;
  private Long sizeBytes;
  private String contentType;
  private String fileBytesMd5;
  private Identity identity;
  private OnConflict onConflict = OnConflict.REJECT;
  private String ingestSource;
  private String indexedContent;
  private String chunkType;
  private Map<String, Object> metadata;
}
