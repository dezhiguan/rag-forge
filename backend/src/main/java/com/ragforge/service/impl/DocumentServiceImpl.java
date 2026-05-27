package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.common.PageResult;
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
import com.ragforge.service.DocumentService;
import com.ragforge.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
  private static final String PARSE_STATUS_PENDING = "pending";

  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("pdf", "doc", "docx", "md", "markdown", "html", "htm");

  @Value("${elasticsearch.host:localhost}")
  private String esHost;

  @Value("${elasticsearch.port:9200}")
  private Integer esPort;

  @Value("${elasticsearch.scheme:http}")
  private String esScheme;

  @Value("${elasticsearch.username:}")
  private String esUsername;

  @Value("${elasticsearch.password:}")
  private String esPassword;

  @Value("${app.es.document-index:document_chunks}")
  private String esDocumentIndex;

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final FileStorageService fileStorageService;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public DocumentUploadResultVO upload(Long kbId, MultipartFile file) {
    KnowledgeBase kb = requireActiveKb(kbId);
    validateFile(file);

    String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
    String fileType = extractExtension(originalFilename);
    String fileMd5 = calculateMd5(file);

    Document existing =
        documentMapper.selectOne(
            new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getFileMd5, fileMd5)
                .ne(Document::getParseStatus, "failed")
                .orderByDesc(Document::getId)
                .last("LIMIT 1"));

    if (existing != null) {
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

    // Update knowledge base counters (chunk pipeline is async; here only create a pending document)
    kb.setDocCount(coalesce(kb.getDocCount(), 0) + 1);
    kb.setUpdatedAt(now);
    knowledgeBaseMapper.updateById(kb);

    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setExists(false);
    result.setDocument(toDocumentVO(doc));
    result.setMessage("上传成功");
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
    String fileType = extractExtension(originalFilename);
    String fileMd5 = calculateMd5(file);

    int oldChunkCount = coalesce(existing.getChunkCount(), 0);

    // delete previous artifacts
    fileStorageService.delete(existing.getFilePath());
    documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, existingDocId));
    deleteEsByDocId(existingDocId);

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

    // kb chunk/doc counters remain unchanged; chunk may need rollback to zero for replaced document
    if (coalesce(kb.getChunkCount(), 0) > 0 && oldChunkCount > 0) {
      kb.setChunkCount(Math.max(0, coalesce(kb.getChunkCount(), 0) - oldChunkCount));
      kb.setUpdatedAt(now);
      knowledgeBaseMapper.updateById(kb);
    }

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

    List<DocumentChunkVO> chunks =
        documentChunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocId, id)
                    .orderByAsc(DocumentChunk::getChunkIndex))
            .stream()
            .map(
                c -> {
                  DocumentChunkVO vo = new DocumentChunkVO();
                  vo.setChunkIndex(c.getChunkIndex());
                  vo.setContent(c.getContent());
                  vo.setTokenCount(c.getTokenCount());
                  return vo;
                })
            .toList();

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
    vo.setCreatedAt(doc.getCreatedAt());
    vo.setChunks(chunks);
    return vo;
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

  @Override
  @Transactional
  public void delete(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "文档不存在");
    }

    // 1) delete physical file (best effort)
    fileStorageService.delete(doc.getFilePath());

    // 2) delete DB record (document_chunks cascade via FK)
    documentMapper.deleteById(id);

    // 3) update knowledge base counters
    KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
    if (kb != null && !STATUS_DELETED.equals(kb.getStatus())) {
      int kbDocCount = coalesce(kb.getDocCount(), 0);
      int kbChunkCount = coalesce(kb.getChunkCount(), 0);
      int docChunkCount = coalesce(doc.getChunkCount(), 0);

      kb.setDocCount(Math.max(0, kbDocCount - 1));
      kb.setChunkCount(Math.max(0, kbChunkCount - docChunkCount));
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

  private String calculateMd5(MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
      byte[] buffer = new byte[8192];
      int len;
      while ((len = is.read(buffer)) != -1) {
        md.update(buffer, 0, len);
      }
      byte[] digest = md.digest();
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new BizException(500, "计算文件 MD5 失败");
    }
  }

  private void deleteEsByDocId(Long docId) {
    try {
      String payload =
          objectMapper.writeValueAsString(
              java.util.Map.of("query", java.util.Map.of("term", java.util.Map.of("doc_id", docId))));

      HttpRequest.Builder builder =
          HttpRequest.newBuilder(
                  URI.create(
                      String.format("%s://%s:%d/%s/_delete_by_query", esScheme, esHost, esPort, esDocumentIndex)))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload));

      if (StringUtils.hasText(esUsername) && StringUtils.hasText(esPassword)) {
        String auth =
            java.util.Base64.getEncoder()
                .encodeToString((esUsername + ":" + esPassword).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + auth);
      }

      HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      log.warn("Failed to cleanup Elasticsearch index for doc_id={}", docId, e);
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.warn("Failed to cleanup Elasticsearch index for doc_id={}", docId, e);
    } catch (Exception e) {
      log.warn("Failed to cleanup Elasticsearch index for doc_id={}", docId, e);
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
    vo.setCreatedAt(doc.getCreatedAt());
    return vo;
  }

  private int coalesce(Integer v, int defaultValue) {
    return v == null ? defaultValue : v;
  }
}

