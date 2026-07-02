package com.ragforge.model.vo;

import com.ragforge.archive.SkipRecord;
import java.util.List;
import lombok.Data;

@Data
public class DocumentDetailVO extends DocumentVO {

  private List<DocumentChunkVO> chunks;
  private String cleanReportJson;
  private Long cleanProfileId;

  /** 是否为压缩包容器（file_type in zip/tar.gz 且无父容器）。普通文档为 false。 */
  private Boolean isArchive;

  /** 容器子文档进度汇总；仅容器返回，普通文档为 null。 */
  private ChildrenSummaryVO childrenSummary;

  /** 容器解压跳过明细（来自 expand_summary.skipped）；仅容器返回，普通文档为 null。 */
  private List<SkipRecord> skippedEntries;
}
