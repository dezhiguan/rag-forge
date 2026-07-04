package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragforge.common.BizException;
import com.ragforge.common.PageResult;
import com.ragforge.document.support.RechunkSupport;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.ChildrenSummaryVO;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.service.DocumentService;
import com.ragforge.service.FileStorageService;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

  private static final int MAX_BYTES = 50 * 1024 * 1024;
  private static final String STATUS_DELETED = "deleted";
  private static final String PARSE_STATUS_PENDING = "PENDING";

  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of(
          "pdf", "doc", "docx", "md", "markdown", "html", "htm", "txt", "csv",
          // 图片：与前端上传保持一致，走图片管道（OCR + 多模态 embedding）
          "png", "jpg", "jpeg", "gif", "webp");

  /** 压缩包容器判定的 file_type 集合（配合 parentDocumentId == null）。 */
  private static final Set<String> ARCHIVE_FILE_TYPES = Set.of("zip", "tar.gz");

  /** 压缩包扩展名：直传接口不解压，命中时给出明确指引（区别于笼统的"不支持格式"）。 */
  private static final Set<String> ARCHIVE_EXTENSIONS =
      Set.of("zip", "tar", "gz", "tgz", "tar.gz", "rar", "7z", "bz2");

  private static final String STATUS_PROCESSING = "PROCESSING";

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final FileStorageService fileStorageService;
  private final ObjectStorage objectStorage;
  private final DocumentProcessProducer documentProcessProducer;
  private final EsIndexService esIndexService;
  private final com.ragforge.search.QdrantVectorStore qdrantVectorStore;
  private final ObjectMapper objectMapper;
  // image_key 单独走原生 JDBC 读，避免 V25 在云上没真正应用时整条 SELECT 500。
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @Override
  @Transactional
  public DocumentUploadResultVO upload(Long kbId, MultipartFile file) {
    return upload(kbId, file, false);
  }

  @Override
  @Transactional
  public DocumentUploadResultVO upload(Long kbId, MultipartFile file, boolean overwrite) {
    KnowledgeBase kb = requireActiveKb(kbId);
    validateFile(file);

    String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
    String fileType = file.getContentType();
    String fileMd5 = calculateSha256(file);

    Document existing =
        documentMapper.selectOne(
            new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getFileMd5, fileMd5)
                .ne(Document::getParseStatus, "FAILED")
                .orderByDesc(Document::getId)
                .last("LIMIT 1"));

    if (existing != null) {
      if (overwrite) {
        DocumentVO replaced = replaceDocument(kbId, file, existing.getId());
        DocumentUploadResultVO result = new DocumentUploadResultVO();
        result.setExists(false);
        result.setDocument(replaced);
        result.setDocumentId(replaced.getId());
        result.setStatus(STATUS_PROCESSING);
        result.setMessage("文件已存在，已覆盖更新并重新处理");
        return result;
      }
      DocumentUploadResultVO result = new DocumentUploadResultVO();
      result.setExists(true);
      result.setExistingDocument(toDocumentVO(existing));
      result.setMessage("文件已存在");
      return result;
    }

    String filePath = fileStorageService.store(file);
    LocalDateTime now = LocalDateTime.now();

    Document doc = new Document();
    doc.setKbId(kb.getId());
    doc.setFilename(originalFilename);
    doc.setFilePath(filePath);
    doc.setFileSize(file.getSize());
    doc.setFileType(fileType);
    doc.setFileMd5(fileMd5);
    doc.setVersion(1);
    doc.setParseStatus(PARSE_STATUS_PENDING);
    doc.setChunkCount(0);
    doc.setErrorMsg(null);
    doc.setCreatedAt(now);

    documentMapper.insert(doc);

    documentProcessProducer.send(doc.getId());

    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setExists(false);
    result.setDocument(toDocumentVO(doc));
    result.setDocumentId(doc.getId());
    result.setStatus(STATUS_PROCESSING);
    result.setMessage("上传成功，正在处理");
    return result;
  }

  @Override
  @Transactional
  public DocumentVO replaceDocument(Long kbId, MultipartFile file, Long existingDocId) {
    KnowledgeBase kb = requireActiveKb(kbId);
    validateFile(file);

    Document existing = documentMapper.selectById(existingDocId);
    if (existing == null || !Objects.equals(existing.getKbId(), kbId)) {
      throw new BizException(404, "文档不存在");
    }

    String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
    String fileType = file.getContentType();
    String fileMd5 = calculateSha256(file);

    int oldChunkCount = coalesce(existing.getChunkCount(), 0);

    // delete previous artifacts
    fileStorageService.delete(existing.getFilePath());
    documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, existingDocId));
    esIndexService.deleteByDocId(existingDocId);
    qdrantVectorStore.deleteByDocId(existingDocId);

    // store new file
    String newFilePath = fileStorageService.store(file);
    LocalDateTime now = LocalDateTime.now();

    existing.setFilename(originalFilename);
    existing.setFilePath(newFilePath);
    existing.setFileSize(file.getSize());
    existing.setFileType(fileType);
    existing.setFileMd5(fileMd5);
    existing.setVersion(coalesce(existing.getVersion(), 1) + 1);
    existing.setParseStatus(PARSE_STATUS_PENDING);
    existing.setChunkCount(0);
    existing.setErrorMsg(null);
    existing.setCreatedAt(now);
    documentMapper.updateById(existing);

    if (coalesce(kb.getChunkCount(), 0) > 0 && oldChunkCount > 0) {
      kb.setChunkCount(Math.max(0, coalesce(kb.getChunkCount(), 0) - oldChunkCount));
      kb.setUpdatedAt(now);
      knowledgeBaseMapper.updateById(kb);
    }

    documentProcessProducer.send(existing.getId());

    return toDocumentVO(existing);
  }

  @Override
  public PageResult<DocumentVO> listByKb(Long kbId, int page, int size, String keyword) {
    return listByKb(kbId, page, size, keyword, false);
  }

  @Override
  public PageResult<DocumentVO> listByKb(
      Long kbId, int page, int size, String keyword, boolean flatten) {
    requireActiveKb(kbId);

    Page<Document> mpPage = new Page<>(page, size);
    String kw = keyword == null ? null : keyword.trim();
    LambdaQueryWrapper<Document> wrapper =
        new LambdaQueryWrapper<Document>()
            .eq(Document::getKbId, kbId)
            .like(kw != null && !kw.isEmpty(), Document::getFilename, kw)
            .orderByDesc(Document::getCreatedAt);
    if (flatten) {
      // 文档列表层：平铺——子文档 + 独立文档，排除压缩包容器本身（容器归"知识库列表"层）。
      wrapper.and(
          q ->
              q.isNotNull(Document::getParentDocumentId)
                  .or(o -> o.notIn(Document::getFileType, ARCHIVE_FILE_TYPES))
                  .or(o -> o.isNull(Document::getFileType)));
    } else {
      // 知识库列表层：原始文件——容器 + 独立文档（隐藏子文档）。
      wrapper.isNull(Document::getParentDocumentId);
    }
    IPage<Document> result = documentMapper.selectPage(mpPage, wrapper);

    List<DocumentVO> list = result.getRecords().stream().map(this::toDocumentVO).toList();
    if (flatten) {
      enrichSourceArchiveNames(list);
    }
    return PageResult.of(result.getTotal(), (int) mpPage.getCurrent(), size, list);
  }

  /** 为平铺列表中的子文档批量补齐来源压缩包文件名（一次查询，避免 N+1）。 */
  private void enrichSourceArchiveNames(List<DocumentVO> list) {
    java.util.Set<Long> parentIds =
        list.stream()
            .map(DocumentVO::getParentDocumentId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    if (parentIds.isEmpty()) {
      return;
    }
    java.util.Map<Long, String> nameById = new java.util.HashMap<>();
    for (Document parent : documentMapper.selectBatchIds(parentIds)) {
      nameById.put(parent.getId(), parent.getFilename());
    }
    for (DocumentVO vo : list) {
      if (vo.getParentDocumentId() != null) {
        vo.setSourceArchiveName(nameById.get(vo.getParentDocumentId()));
      }
    }
  }

  @Override
  public DocumentDetailVO getById(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }

    KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
    DocumentDetailVO vo = new DocumentDetailVO();
    vo.setId(doc.getId());
    vo.setKbId(doc.getKbId());
    vo.setFilename(doc.getFilename());
    vo.setFileSize(doc.getFileSize());
    vo.setFileType(doc.getFileType());
    vo.setFileMd5(doc.getFileMd5());
    vo.setVersion(doc.getVersion());
    vo.setParseStatus(doc.getParseStatus());
    vo.setChunkCount(doc.getChunkCount());
    vo.setErrorMsg(doc.getErrorMsg());
    vo.setExternalId(doc.getExternalId());
    vo.setSourceUrl(doc.getSourceUrl());
    vo.setContentMd5(doc.getContentMd5());
    vo.setIngestSource(doc.getIngestSource());
    vo.setCleanReportJson(doc.getCleanReportJson());
    vo.setCleanProfileId(doc.getCleanProfileId());
    vo.setCreatedAt(doc.getCreatedAt());
    // 子文档来源标识（详情页「来自 xx.zip」）
    vo.setParentDocumentId(doc.getParentDocumentId());
    vo.setArchiveEntryPath(doc.getArchiveEntryPath());
    if (doc.getParentDocumentId() != null) {
      Document parent = documentMapper.selectById(doc.getParentDocumentId());
      vo.setSourceArchiveName(parent == null ? null : parent.getFilename());
    }
    if (kb != null) {
      vo.setKbName(kb.getName());
      vo.setEmbeddingModel(kb.getEmbeddingModel());
      vo.setChunkSize(kb.getChunkSize());
      vo.setChunkOverlap(kb.getChunkOverlap());
    }
    applyEffectiveChunkParams(vo, id);
    vo.setChunks(List.of());
    applyArchiveAggregation(vo, doc);
    return vo;
  }

  /**
   * 容器详情聚合：若该文档为压缩包容器（file_type in zip/tar.gz 且无父容器），
   * 实时按子文档状态计算 childrenSummary 并解析 expand_summary.skipped，普通文档保持 false/null。
   */
  private void applyArchiveAggregation(DocumentDetailVO vo, Document doc) {
    if (!isArchiveContainer(doc)) {
      vo.setIsArchive(false);
      return;
    }
    vo.setIsArchive(true);

    ChildrenSummaryVO summary = new ChildrenSummaryVO();
    int total = 0;
    List<java.util.Map<String, Object>> rows = documentMapper.countChildrenByStatus(doc.getId());
    if (rows != null) {
      for (java.util.Map<String, Object> row : rows) {
        if (row == null) {
          continue;
        }
        Object statusObj = row.get("status");
        String status = statusObj == null ? "" : statusObj.toString().toUpperCase(Locale.ROOT);
        int cnt = toInt(row.get("cnt"));
        total += cnt;
        switch (status) {
          case "PENDING" -> summary.setPending(cnt);
          case "PROCESSING" -> summary.setProcessing(cnt);
          case "COMPLETED" -> summary.setCompleted(cnt);
          case "FAILED" -> summary.setFailed(cnt);
          default -> {
            // 未知状态仍计入 total，但不单列，避免前端出现未定义字段。
          }
        }
      }
    }
    summary.setTotal(total);

    List<com.ragforge.archive.SkipRecord> skipped = parseSkippedEntries(doc.getExpandSummary());
    summary.setSkipped(skipped.size());
    vo.setChildrenSummary(summary);
    vo.setSkippedEntries(skipped);
  }

  /** 解析容器 expand_summary.skipped 数组为 [{path, reason}]；缺失/非法时返回空列表。 */
  private List<com.ragforge.archive.SkipRecord> parseSkippedEntries(String expandSummary) {
    if (!StringUtils.hasText(expandSummary)) {
      return List.of();
    }
    try {
      JsonNode root = objectMapper.readTree(expandSummary);
      JsonNode skippedNode = root.get("skipped");
      if (skippedNode == null || !skippedNode.isArray()) {
        return List.of();
      }
      List<com.ragforge.archive.SkipRecord> list = new java.util.ArrayList<>();
      for (JsonNode node : skippedNode) {
        com.ragforge.archive.SkipRecord record = new com.ragforge.archive.SkipRecord();
        record.setPath(node.hasNonNull("path") ? node.get("path").asText() : null);
        record.setReason(node.hasNonNull("reason") ? node.get("reason").asText() : null);
        list.add(record);
      }
      return list;
    } catch (Exception e) {
      log.debug("parseSkippedEntries skipped: {}", e.getMessage());
      return List.of();
    }
  }

  private boolean isArchiveContainer(Document doc) {
    return doc.getParentDocumentId() == null
        && doc.getFileType() != null
        && ARCHIVE_FILE_TYPES.contains(doc.getFileType());
  }

  private int toInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public List<DocumentVO> listChildren(Long containerId) {
    Document container = documentMapper.selectById(containerId);
    if (container == null || !isArchiveContainer(container)) {
      throw new BizException(404, "文档不存在");
    }
    List<Document> children = documentMapper.selectChildren(containerId);
    return children.stream().map(this::toDocumentVO).toList();
  }

  /** 文档详情展示实际分块参数（rechunk 后覆盖 KB 默认值）。 */
  private void applyEffectiveChunkParams(DocumentDetailVO vo, Long docId) {
    DocumentChunk sample =
        documentChunkMapper.selectOne(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, docId)
                .and(
                    w ->
                        w.isNull(DocumentChunk::getChunkModality)
                            .or()
                            .apply("upper(chunk_modality) not like 'IMAGE%'"))
                .orderByAsc(DocumentChunk::getChunkIndex)
                .last("LIMIT 1"));
    if (sample == null
        || !StringUtils.hasText(sample.getChunkerStrategy())
        || !RechunkSupport.usesFixedParams(sample.getChunkerStrategy())
        || !StringUtils.hasText(sample.getChunkerParamsJson())) {
      return;
    }
    try {
      JsonNode node = objectMapper.readTree(sample.getChunkerParamsJson());
      if (node.hasNonNull("chunkSize")) {
        vo.setChunkSize(node.get("chunkSize").asInt());
      }
      if (node.hasNonNull("overlap")) {
        vo.setChunkOverlap(node.get("overlap").asInt());
      }
    } catch (Exception e) {
      log.debug("applyEffectiveChunkParams skipped: docId={} {}", docId, e.getMessage());
    }
  }

  @Override
  public PageResult<DocumentChunkVO> listChunks(Long id, int page, int size, String keyword) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }

    String kw = keyword == null ? null : keyword.trim();
    // #编号 定位：kw 形如 "#12" 时按 chunk_index 精确定位（限 9 位以内，避免溢出）；否则按 content 模糊搜。
    Integer jumpIndex = null;
    if (kw != null && kw.matches("#\\d{1,9}")) {
      jumpIndex = Integer.parseInt(kw.substring(1));
    }
    boolean contentLike = jumpIndex == null && kw != null && !kw.isEmpty();
    int pageSize = Math.min(size, 100);
    Page<DocumentChunk> mpPage = new Page<>(page, pageSize);
    IPage<DocumentChunk> result =
        documentChunkMapper.selectPage(
            mpPage,
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, id)
                .eq(jumpIndex != null, DocumentChunk::getChunkIndex, jumpIndex)
                .like(contentLike, DocumentChunk::getContent, kw)
                .orderByAsc(DocumentChunk::getChunkIndex));

    String storageBucket = doc.getStorageBucket();
    java.util.Map<Long, String> imageKeyMap = fetchImageKeys(result.getRecords());
    List<DocumentChunkVO> list =
        result.getRecords().stream()
            .map((c) -> toChunkVO(c, storageBucket, imageKeyMap.get(c.getId())))
            .toList();
    return PageResult.of(result.getTotal(), (int) mpPage.getCurrent(), pageSize, list);
  }

  /**
   * 批量取 image_key。entity 标了 exist=false 不走 MyBatis，列存在与否都不影响主 SELECT；
   * 这里再加 try/catch 兜底：列不存在时返回空 map，详情页只是缺图预览 URL，
   * chunk 列表本身照常返回。
   */
  private java.util.Map<Long, String> fetchImageKeys(List<DocumentChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) return java.util.Map.of();
    List<Long> ids = chunks.stream().map(DocumentChunk::getId).filter(java.util.Objects::nonNull).toList();
    if (ids.isEmpty()) return java.util.Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    String sql = "SELECT id, image_key FROM document_chunks WHERE id IN (" + placeholders + ")";
    java.util.Map<Long, String> map = new java.util.HashMap<>();
    try {
      jdbcTemplate.query(
          sql,
          ids.toArray(),
          rs -> {
            long id = rs.getLong(1);
            String key = rs.getString(2);
            if (key != null && !key.isEmpty()) map.put(id, key);
          });
    } catch (Exception e) {
      log.warn(
          "fetchImageKeys skipped (column likely missing on this env): chunkIds={} err={}",
          ids.size(),
          e.getMessage());
    }
    return map;
  }

  @Override
  public DocumentStatusVO getStatus(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }
    DocumentStatusVO vo = new DocumentStatusVO();
    vo.setParseStatus(doc.getParseStatus());
    vo.setChunkCount(doc.getChunkCount());
    vo.setErrorMsg(doc.getErrorMsg());
    return vo;
  }

  @Override
  public ResponseEntity<Resource> download(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }

    if (StringUtils.hasText(doc.getStorageBucket())) {
      return downloadFromObjectStorage(doc);
    }

    if (!StringUtils.hasText(doc.getFilePath())) {
      throw new BizException(404, "原文件不存在");
    }

    FileSystemResource resource = new FileSystemResource(doc.getFilePath());
    if (!resource.exists()) {
      throw new BizException(404, "原文件不存在");
    }

    String filename = StringUtils.hasText(doc.getFilename()) ? doc.getFilename() : resource.getFilename();
    ContentDisposition disposition =
        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();

    try {
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .contentLength(resource.contentLength())
          .body(resource);
    } catch (IOException e) {
      throw new BizException(500, "读取文件失败");
    }
  }

  private ResponseEntity<Resource> downloadFromObjectStorage(Document doc) {
    String bucket = doc.getStorageBucket();
    String key = doc.getStorageKey();
    if (!StringUtils.hasText(key)) {
      throw new BizException(404, "原文件不存在");
    }

    ObjectMeta meta;
    try {
      meta = objectStorage.head(bucket, key);
    } catch (RuntimeException e) {
      throw new BizException(500, "读取文件失败");
    }

    if (meta == null) {
      throw new BizException(404, "原文件不存在");
    }

    InputStream stream;
    try {
      stream = objectStorage.get(bucket, key);
    } catch (RuntimeException e) {
      throw new BizException(500, "读取文件失败");
    }

    String filename = StringUtils.hasText(doc.getFilename()) ? doc.getFilename() : key;
    ContentDisposition disposition =
        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();

    var response = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM);
    if (meta.getSizeBytes() != null) {
      response = response.contentLength(meta.getSizeBytes());
    }
    return response.body(new InputStreamResource(stream));
  }

  @Override
  public void reprocess(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }
    if (!"FAILED".equals(doc.getParseStatus())) {
      throw new BizException("只有失败状态的文档可以重试");
    }

    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, id)
            .set(Document::getParseStatus, PARSE_STATUS_PENDING)
            .set(Document::getErrorMsg, null)
            .set(Document::getChunkCount, 0));

    documentProcessProducer.send(id);
  }

  @Override
  @Transactional
  public void delete(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }

    // 收集需在事务外清理的外部产物(ES/Qdrant 索引 + 本地文件):不可回滚的网络/磁盘 IO 不进事务,
    // 移到 afterCommit,避免"事务内删外部 → 后续 DB 回滚 → 外部已删"的孤儿/不一致(设计稿 §5「不留孤儿」)。
    // 压缩包容器：DB 的 ON DELETE CASCADE 只删子文档行与其 chunk 行，ES/Qdrant 不会级联,须逐个收集。
    List<Long> indexDocIds = new ArrayList<>();
    List<String> filePaths = new ArrayList<>();
    if (isArchiveContainer(doc)) {
      for (Document child : documentMapper.selectChildren(id)) {
        indexDocIds.add(child.getId());
        if (child.getFilePath() != null && !child.getFilePath().isBlank()) {
          filePaths.add(child.getFilePath());
        }
      }
    }
    indexDocIds.add(id);
    if (doc.getFilePath() != null && !doc.getFilePath().isBlank()) {
      filePaths.add(doc.getFilePath());
    }

    documentMapper.deleteById(id);

    // KB 计数原子回减(只对已完成文档,与入库自增对称):col=col-delta 避免读改写丢更新 / updateById 全行覆盖;
    // status<>'deleted' 的守卫在 SQL 里,无需先 selectById。
    if ("COMPLETED".equals(doc.getParseStatus())) {
      knowledgeBaseMapper.adjustCounters(doc.getKbId(), -coalesce(doc.getChunkCount(), 0), -1);
    }

    afterCommit(
        () -> {
          for (Long docId : indexDocIds) {
            try {
              esIndexService.deleteByDocId(docId);
              qdrantVectorStore.deleteByDocId(docId);
            } catch (Exception e) {
              log.warn("删除文档后索引清理失败 docId={}: {}", docId, e.getMessage());
            }
          }
          for (String path : filePaths) {
            try {
              fileStorageService.delete(path);
            } catch (Exception e) {
              log.warn("删除文档后文件清理失败 path={}: {}", path, e.getMessage());
            }
          }
        });
  }

  /**
   * 事务提交后执行给定动作;无活动事务时(如单测)立即执行。用于把不可回滚的外部清理(ES/Qdrant/文件)
   * 移出事务边界,保证 DB 为准、外部为 best-effort(失败由 EsIndexRepairJob / 对账作业兜底)。
   */
  private void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private KnowledgeBase requireActiveKb(Long kbId) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || STATUS_DELETED.equals(kb.getStatus())) {
      throw new BizException(404, "知识库不存在");
    }
    return kb;
  }

  private void validateFile(MultipartFile file) {
    if (file.isEmpty()) {
      throw new BizException(400, "文件不能为空");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new BizException(400, "文件最大 50MB");
    }

    String originalFilename = file.getOriginalFilename();
    if (!StringUtils.hasText(originalFilename)) {
      throw new BizException(400, "文件名不能为空");
    }

    String ext = extractExtension(originalFilename);
    if (ARCHIVE_EXTENSIONS.contains(ext)) {
      // 压缩包需走带自动解压的上传通道（前端上传/presign），此直传接口不做解压，明确提示而非笼统拒绝。
      throw new BizException(
          400, "压缩包请通过前端上传（会自动解压并批量入库），当前直传接口暂不支持压缩包");
    }
    if (!ALLOWED_EXTENSIONS.contains(ext)) {
      throw new BizException(
          400, "不支持的文件格式，仅支持 PDF / Word / Markdown / HTML / TXT / CSV / 图片(PNG/JPG/GIF/WEBP)");
    }

    // MIME 校验：contentType 可能为空，此时仅依赖扩展名
    String contentType = file.getContentType();
    if (!StringUtils.hasText(contentType)) {
      return;
    }
    contentType = contentType.toLowerCase(Locale.ROOT);

    boolean ok =
        switch (ext) {
          case "pdf" -> contentType.equals("application/pdf");
          case "doc" -> contentType.equals("application/msword");
          case "docx" ->
              contentType.equals(
                  "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
          case "md", "markdown" -> contentType.equals("text/markdown") || contentType.equals("text/plain");
          case "html", "htm" -> contentType.equals("text/html");
          case "txt" -> contentType.equals("text/plain") || contentType.equals("text/markdown");
          case "csv" ->
              contentType.equals("text/csv")
                  || contentType.equals("application/csv")
                  || contentType.equals("application/vnd.ms-excel")
                  || contentType.equals("text/plain");
          case "png" -> contentType.equals("image/png");
          case "jpg", "jpeg" -> contentType.equals("image/jpeg") || contentType.equals("image/jpg");
          case "gif" -> contentType.equals("image/gif");
          case "webp" -> contentType.equals("image/webp");
          default -> false;
        };

    // 部分浏览器会给通用类型：application/octet-stream，此处容忍但仍要求扩展名在白名单
    if (!ok && contentType.equals("application/octet-stream")) {
      ok = true;
    }

    if (!ok) {
      throw new BizException(400, "不支持的文件类型（MIME 不匹配）");
    }
  }

  private String extractExtension(String filename) {
    int idx = filename.lastIndexOf('.');
    if (idx < 0 || idx == filename.length() - 1) {
      return "";
    }
    return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
  }

  private String calculateSha256(MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int len;
      while ((len = is.read(buffer)) != -1) {
        md.update(buffer, 0, len);
      }
      byte[] digest = md.digest();
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new BizException(500, "计算文件摘要失败");
    }
  }

  private DocumentVO toDocumentVO(Document doc) {
    DocumentVO vo = new DocumentVO();
    vo.setId(doc.getId());
    vo.setKbId(doc.getKbId());
    vo.setFilename(doc.getFilename());
    vo.setFileSize(doc.getFileSize());
    vo.setFileType(doc.getFileType());
    vo.setFileMd5(doc.getFileMd5());
    vo.setVersion(doc.getVersion());
    vo.setParseStatus(doc.getParseStatus());
    vo.setChunkCount(doc.getChunkCount());
    vo.setErrorMsg(doc.getErrorMsg());
    vo.setExternalId(doc.getExternalId());
    vo.setSourceUrl(doc.getSourceUrl());
    vo.setContentMd5(doc.getContentMd5());
    vo.setIngestSource(doc.getIngestSource());
    vo.setCreatedAt(doc.getCreatedAt());
    vo.setIsArchive(isArchiveContainer(doc));
    vo.setParentDocumentId(doc.getParentDocumentId());
    vo.setArchiveEntryPath(doc.getArchiveEntryPath());
    return vo;
  }

  private static final java.time.Duration IMAGE_PREVIEW_TTL = java.time.Duration.ofMinutes(10);

  private DocumentChunkVO toChunkVO(DocumentChunk chunk) {
    return toChunkVO(chunk, null, null);
  }

  private DocumentChunkVO toChunkVO(DocumentChunk chunk, String storageBucket, String imageKey) {
    DocumentChunkVO vo = new DocumentChunkVO();
    vo.setChunkIndex(chunk.getChunkIndex());
    vo.setContent(chunk.getContent());
    vo.setTokenCount(chunk.getTokenCount());
    vo.setChunkerStrategy(chunk.getChunkerStrategy());
    vo.setHeadingPath(chunk.getHeadingPath());
    vo.setChunkModality(chunk.getChunkModality());
    vo.setImageKey(imageKey);
    // 只对真正落库的 IMAGE chunk 签 GET URL，签失败时静默退化为不带预览
    // （前端 v-if 会自动隐藏 <img>），不影响 chunk 列表整体返回。
    if (StringUtils.hasText(imageKey) && StringUtils.hasText(storageBucket)) {
      try {
        vo.setImageUrl(objectStorage.presignedGet(storageBucket, imageKey, IMAGE_PREVIEW_TTL));
      } catch (Exception e) {
        log.warn(
            "Skip chunk image preview URL: chunkIndex={} imageKey={} err={}",
            chunk.getChunkIndex(),
            imageKey,
            e.getMessage());
      }
    }
    // 从 chunk_metadata_json 里挖 figureIndex 暴露给前端：TEXT chunk 的 rfimg://N
    // 占位符按 figureIndex 反查到这条 IMAGE chunk 的 imageUrl 做 inline 渲染。
    if (StringUtils.hasText(chunk.getChunkMetadataJson())) {
      try {
        com.fasterxml.jackson.databind.JsonNode node =
            objectMapper.readTree(chunk.getChunkMetadataJson());
        com.fasterxml.jackson.databind.JsonNode figNode = node.get("figureIndex");
        if (figNode != null && figNode.isInt()) {
          vo.setFigureIndex(figNode.intValue());
        }
      } catch (Exception ignored) {
        // metadata 解析失败不影响 chunk 主体返回
      }
    }
    return vo;
  }

  private int coalesce(Integer v, int defaultValue) {
    return v == null ? defaultValue : v;
  }
}
