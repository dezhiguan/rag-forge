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
      Set.of("pdf", "doc", "docx", "md", "markdown", "html", "htm");

  /** 压缩包容器判定的 file_type 集合（配合 parentDocumentId == null）。 */
  private static final Set<String> ARCHIVE_FILE_TYPES = Set.of("zip", "tar.gz");

  private static final String STATUS_PROCESSING = "PROCESSING";

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final FileStorageService fileStorageService;
  private final ObjectStorage objectStorage;
  private final DocumentProcessProducer documentProcessProducer;
  private final EsIndexService esIndexService;
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
    requireActiveKb(kbId);

    Page<Document> mpPage = new Page<>(page, size);
    String kw = keyword == null ? null : keyword.trim();
    IPage<Document> result =
        documentMapper.selectPage(
            mpPage,
            new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                // 默认隐藏压缩包子文档：列表只呈现容器 + 独立文档，子文档经容器详情下钻查看，
                // 避免一个百文件的压缩包解压产物淹没列表。
                .isNull(Document::getParentDocumentId)
                .like(kw != null && !kw.isEmpty(), Document::getFilename, kw)
                .orderByDesc(Document::getCreatedAt));

    List<DocumentVO> list = result.getRecords().stream().map(this::toDocumentVO).toList();
    return PageResult.of(result.getTotal(), (int) mpPage.getCurrent(), size, list);
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
  public PageResult<DocumentChunkVO> listChunks(Long id, int page, int size) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }

    int pageSize = Math.min(size, 100);
    Page<DocumentChunk> mpPage = new Page<>(page, pageSize);
    IPage<DocumentChunk> result =
        documentChunkMapper.selectPage(
            mpPage,
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, id)
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

    // 压缩包容器：DB 的 ON DELETE CASCADE 只删子文档行与其 chunk 行，ES 索引不会级联。
    // 删容器前须逐个清理子文档的 ES 与本地文件，避免留下 ES 孤儿（设计稿 §5「不留孤儿」）。
    if (isArchiveContainer(doc)) {
      for (Document child : documentMapper.selectChildren(id)) {
        esIndexService.deleteByDocId(child.getId());
        if (child.getFilePath() != null && !child.getFilePath().isBlank()) {
          try {
            fileStorageService.delete(child.getFilePath());
          } catch (Exception ignored) {
            // 子文件清理 best-effort，不阻断容器删除
          }
        }
      }
    }

    esIndexService.deleteByDocId(id);

    fileStorageService.delete(doc.getFilePath());

    documentMapper.deleteById(id);

    // 3) update knowledge base counters
    KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
    if (kb != null && !STATUS_DELETED.equals(kb.getStatus())) {
      int kbDocCount = coalesce(kb.getDocCount(), 0);
      int kbChunkCount = coalesce(kb.getChunkCount(), 0);
      int docChunkCount = coalesce(doc.getChunkCount(), 0);

      if ("COMPLETED".equals(doc.getParseStatus())) {
        kb.setDocCount(Math.max(0, kbDocCount - 1));
        kb.setChunkCount(Math.max(0, kbChunkCount - docChunkCount));
      }
      kb.setUpdatedAt(LocalDateTime.now());
      knowledgeBaseMapper.updateById(kb);
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
    if (!ALLOWED_EXTENSIONS.contains(ext)) {
      throw new BizException(400, "只允许 PDF / Word / Markdown / HTML");
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
