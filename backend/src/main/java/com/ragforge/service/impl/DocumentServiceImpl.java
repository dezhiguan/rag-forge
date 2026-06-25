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

  private static final String STATUS_PROCESSING = "PROCESSING";

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final FileStorageService fileStorageService;
  private final ObjectStorage objectStorage;
  private final DocumentProcessProducer documentProcessProducer;
  private final EsIndexService esIndexService;
  private final ObjectMapper objectMapper;

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
  public PageResult<DocumentVO> listByKb(Long kbId, int page, int size) {
    requireActiveKb(kbId);

    Page<Document> mpPage = new Page<>(page, size);
    IPage<Document> result =
        documentMapper.selectPage(
            mpPage,
            new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
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
    return vo;
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
    List<DocumentChunkVO> list =
        result.getRecords().stream().map((c) -> toChunkVO(c, storageBucket)).toList();
    return PageResult.of(result.getTotal(), (int) mpPage.getCurrent(), pageSize, list);
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
    return vo;
  }

  private static final java.time.Duration IMAGE_PREVIEW_TTL = java.time.Duration.ofMinutes(10);

  private DocumentChunkVO toChunkVO(DocumentChunk chunk) {
    return toChunkVO(chunk, null);
  }

  private DocumentChunkVO toChunkVO(DocumentChunk chunk, String storageBucket) {
    DocumentChunkVO vo = new DocumentChunkVO();
    vo.setChunkIndex(chunk.getChunkIndex());
    vo.setContent(chunk.getContent());
    vo.setTokenCount(chunk.getTokenCount());
    vo.setChunkerStrategy(chunk.getChunkerStrategy());
    vo.setHeadingPath(chunk.getHeadingPath());
    vo.setChunkModality(chunk.getChunkModality());
    vo.setImageKey(chunk.getImageKey());
    // 只对真正落库的 IMAGE chunk 签 GET URL，签失败时静默退化为不带预览
    // （前端 v-if 会自动隐藏 <img>），不影响 chunk 列表整体返回。
    if (StringUtils.hasText(chunk.getImageKey()) && StringUtils.hasText(storageBucket)) {
      try {
        vo.setImageUrl(objectStorage.presignedGet(storageBucket, chunk.getImageKey(), IMAGE_PREVIEW_TTL));
      } catch (Exception e) {
        log.warn(
            "Skip chunk image preview URL: chunkIndex={} imageKey={} err={}",
            chunk.getChunkIndex(),
            chunk.getImageKey(),
            e.getMessage());
      }
    }
    return vo;
  }

  private int coalesce(Integer v, int defaultValue) {
    return v == null ? defaultValue : v;
  }
}
