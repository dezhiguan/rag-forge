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

  /**
   * 压缩包格式标识（zip / tar.gz）。非空表示这是一个"容器文档"登记：doCreate 会把 file_type 设为该 token，
   * 并在 afterCommit 派发到 {@code ragforge-archive-expand} 而非普通文档处理管道。普通文档与子文档为 null。
   */
  private String archiveFormat;

  /** 压缩包子文档指向容器 id（子文档登记时设置；容器与普通文档为 null）。 */
  private Long parentDocumentId;

  /** 子文档在压缩包内的相对路径（子文档登记时设置）。 */
  private String archiveEntryPath;
}
