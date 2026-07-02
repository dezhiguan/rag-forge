package com.ragforge.document.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.archive.ArchiveErrorCodes;
import com.ragforge.archive.ArchiveFormat;
import com.ragforge.archive.ArchiveFormatDetector;
import com.ragforge.common.BizException;
import com.ragforge.common.RelayUploadLimits;
import com.ragforge.document.dto.UploadRelayResult;
import com.ragforge.document.service.DocumentUploadApplicationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragforge.document.support.RechunkSupport;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.model.dto.RechunkRequest;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.chunker.ChunkParams;
import com.ragforge.pipeline.chunker.ChunkerProfile;
import com.ragforge.pipeline.chunker.ChunkingService;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.PresignUploadRequest;
import com.ragforge.model.dto.RegisterUploadRequest;
import com.ragforge.model.entity.Document;
import com.ragforge.mq.ArchiveExpandProducer;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.ingest.IngestService;
import com.ragforge.service.upload.UploadTokenService;
import com.ragforge.service.upload.UploadTokenService.TokenPayload;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.storage.StorageProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentUploadApplicationServiceImpl implements DocumentUploadApplicationService {

  private final IngestService ingestService;
  private final ObjectStorage objectStorage;
  private final StorageProperties storageProperties;
  private final KbAccessGuard kbAccessGuard;
  private final ObjectMapper objectMapper;
  private final UploadTokenService uploadTokenService;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final ChunkingService chunkingService;
  private final DocumentProcessProducer mqProducer;
  private final ArchiveExpandProducer archiveExpandProducer;
  private final ArchiveFormatDetector archiveFormatDetector;

  @Override
  public Map<String, Object> presignUpload(PresignUploadRequest request) {
    if (request.getKbId() == null || !kbAccessGuard.canWrite(request.getKbId())) {
      throw new BizException(403, "KB_WRITE_FORBIDDEN");
    }
    if (request.getDeclaredSize() == null || request.getDeclaredSize() <= 0) {
      throw new BizException(400, "DECLARED_SIZE_REQUIRED");
    }
    String filename = cleanFilename(request.getFilename());
    String bucket = defaultBucket(null);
    // 存储键中段用 UUID（[0-9a-f-]）而非 uplt_ 前缀，保证 storageKey 形如
    // kb_{kbId}/{uuid}/{filename}，与 relay 路径一致且符合无 tenant 段的约定。
    String key = storageKey(request.getKbId(), filename);

    String safeContentType = request.getContentType();
    if (safeContentType == null || safeContentType.isBlank()) {
      safeContentType = "application/octet-stream";
    }

    TokenPayload payload = new TokenPayload();
    payload.setKbId(request.getKbId());
    payload.setStorageBucket(bucket);
    payload.setStorageKey(key);
    payload.setFilename(filename);
    payload.setContentType(safeContentType);
    payload.setDeclaredSize(request.getDeclaredSize());
    String token = uploadTokenService.issue(payload);

    ObjectMeta meta = new ObjectMeta();
    meta.setContentType(safeContentType);
    meta.setSizeBytes(request.getDeclaredSize());
    meta.setUserMeta(Map.of("kb-id", String.valueOf(request.getKbId())));
    String presignedPutUrl =
        objectStorage.presignedPut(bucket, key, uploadTokenService.tokenTtl(), meta);

    return Map.of(
        "uploadToken",
        token,
        "presignedPutUrl",
        presignedPutUrl,
        "storageBucket",
        bucket,
        "storageKey",
        key,
        "expiresAt",
        Instant.ofEpochSecond(payload.getExpiresAt()).toString());
  }

  @Override
  public Map<String, Object> registerUploadedDocument(RegisterUploadRequest request) {
    // 分段计时：云上 acc-11 复现"register 120s 超时"时，必须能从日志直接定位
    // 到具体卡在 token consume / OSS head / ingest.register 哪一步。
    long t0 = System.currentTimeMillis();
    TokenPayload payload = uploadTokenService.consume(request.getUploadToken());
    long t1 = System.currentTimeMillis();
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null) {
      throw new BizException(403, "UPLOAD_TOKEN_FORBIDDEN");
    }
    // 归属校验：token 绑定的 kbId 必须与请求一致，防止持 A 库 token 写 B 库（写权限已由 @PreAuthorize canWrite 拦截）
    if (!payload.getKbId().equals(request.getKbId())) {
      throw new BizException(403, "UPLOAD_TOKEN_KB_FORBIDDEN");
    }

    ObjectMeta head = objectStorage.head(payload.getStorageBucket(), payload.getStorageKey());
    long t2 = System.currentTimeMillis();
    if (head == null) {
      log.warn(
          "register: object missing bucket={} key={} contentType={} declared={} (consumeMs={} headMs={})",
          payload.getStorageBucket(),
          payload.getStorageKey(),
          payload.getContentType(),
          payload.getDeclaredSize(),
          t1 - t0,
          t2 - t1);
      throw new BizException(422, "UPLOAD_NOT_FOUND");
    }
    if (head.getSizeBytes() == null || !head.getSizeBytes().equals(payload.getDeclaredSize())) {
      log.warn(
          "register: size mismatch bucket={} key={} headSize={} declared={} headContentType={} payloadContentType={}",
          payload.getStorageBucket(),
          payload.getStorageKey(),
          head.getSizeBytes(),
          payload.getDeclaredSize(),
          head.getContentType(),
          payload.getContentType());
      throw new BizException(422, "SIZE_MISMATCH");
    }

    // 分流：按 magic number 判定是否压缩包。7z/rar 直接拒；zip/tar.gz 标记为容器登记（走展开管道）。
    ArchiveFormat archiveFormat =
        detectArchiveFormat(payload.getStorageBucket(), payload.getStorageKey());
    if (archiveFormat.isArchive() && !archiveFormat.isSupported()) {
      safeDelete(payload.getStorageBucket(), payload.getStorageKey());
      throw new BizException(415, ArchiveErrorCodes.UNSUPPORTED_FORMAT);
    }

    IngestCommand cmd = new IngestCommand();
    cmd.setKbId(payload.getKbId());
    cmd.setStorageBucket(payload.getStorageBucket());
    cmd.setStorageKey(payload.getStorageKey());
    cmd.setFilename(payload.getFilename());
    cmd.setSizeBytes(head.getSizeBytes());
    cmd.setContentType(head.getContentType());
    cmd.setIdentity(request.getIdentity());
    cmd.setOnConflict(request.getOnConflict());
    cmd.setIngestSource(request.getIngestSource());
    cmd.setChunkType(request.getChunkType());
    cmd.setMetadata(request.getMetadata());
    if (archiveFormat.isSupported()) {
      cmd.setArchiveFormat(archiveFormat.token());
    }

    IngestResult result = ingestService.register(cmd);
    long t3 = System.currentTimeMillis();
    if (result.getStatus() == IngestResult.Status.SKIPPED) {
      safeDelete(payload.getStorageBucket(), payload.getStorageKey());
    }
    log.info(
        "register ok: docId={} contentType={} size={} consumeMs={} headMs={} ingestMs={} totalMs={}",
        result.getDocumentId(),
        head.getContentType(),
        head.getSizeBytes(),
        t1 - t0,
        t2 - t1,
        t3 - t2,
        t3 - t0);
    return ingestResponse(result);
  }

  @Override
  public UploadRelayResult relayUpload(MultipartFile file, String metaJson) {
    IngestCommand cmd = parseMeta(metaJson);
    Long kbId = cmd.getKbId();
    if (!kbAccessGuard.canWrite(kbId)) {
      throw new BizException(403, "KB_WRITE_FORBIDDEN");
    }
    if (file.getSize() > RelayUploadLimits.RELAY_UPLOAD_LIMIT_BYTES) {
      return UploadRelayResult.payloadTooLarge(
          Map.of(
              "error",
              "FILE_TOO_LARGE_FOR_RELAY",
              "presignUrl",
              RelayUploadLimits.PRESIGN_URL,
              "limitMb",
              RelayUploadLimits.RELAY_UPLOAD_LIMIT_MB));
    }

    Path tempFile = null;
    String bucket = defaultBucket(cmd.getStorageBucket());
    String key = null;
    try {
      tempFile = Files.createTempFile("ragforge-upload-", ".bin");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream raw = file.getInputStream();
          DigestInputStream in = new DigestInputStream(raw, digest);
          var out = Files.newOutputStream(tempFile)) {
        in.transferTo(out);
      }
      String fileContentHash = HexFormat.of().formatHex(digest.digest());
      applyFileMd5(cmd, fileContentHash);

      // 分流：relay 通道同样按 magic number 判定压缩包（拒 7z/rar；zip/tar.gz 标记容器）
      ArchiveFormat archiveFormat = detectArchiveFormatFromFile(tempFile);
      if (archiveFormat.isArchive() && !archiveFormat.isSupported()) {
        throw new BizException(415, ArchiveErrorCodes.UNSUPPORTED_FORMAT);
      }
      if (archiveFormat.isSupported()) {
        cmd.setArchiveFormat(archiveFormat.token());
      }

      String originalFilename = cleanFilename(file.getOriginalFilename());
      key = storageKey(kbId, originalFilename);
      cmd.setStorageBucket(bucket);
      cmd.setStorageKey(key);
      cmd.setFilename(originalFilename);
      cmd.setSizeBytes(file.getSize());
      cmd.setContentType(file.getContentType());

      ObjectMeta objectMeta = new ObjectMeta();
      objectMeta.setContentType(file.getContentType());
      objectMeta.setSizeBytes(file.getSize());
      objectMeta.setUserMeta(Map.of("content-sha256", fileContentHash, "kb-id", String.valueOf(kbId)));
      try (InputStream in = Files.newInputStream(tempFile)) {
        objectStorage.put(bucket, key, in, objectMeta);
      }

      IngestResult result = ingestService.register(cmd);
      if (result.getStatus() == IngestResult.Status.SKIPPED) {
        safeDelete(bucket, key);
      }
      return UploadRelayResult.ok(ingestResponse(result));
    } catch (BizException e) {
      if (key != null && e.getCode() == 409) {
        safeDelete(bucket, key);
      }
      if (e.getCode() == 409 && "DOC_IDENTITY_CONFLICT".equals(e.getMessage())) {
        return new UploadRelayResult(
            org.springframework.http.HttpStatus.CONFLICT,
            Map.of(
                "error",
                "DOC_IDENTITY_CONFLICT",
                "existingDocId",
                e.getData() == null ? "" : e.getData().getOrDefault("existingDocId", "")));
      }
      throw e;
    } catch (Exception e) {
      if (key != null) {
        safeDelete(bucket, key);
      }
      log.warn("V5 relay upload failed", e);
      throw new BizException(500, "DOCUMENT_UPLOAD_FAILED");
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (Exception ignored) {
          // 临时文件清理失败不影响请求结果。
        }
      }
    }
  }

  @Override
  @Transactional
  public Map<String, Object> reprocess(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "DOCUMENT_NOT_FOUND");
    }

    // 压缩包容器整包重试：FAILED/EXPANDED → 重新 EXPANDING，派发到解压管道（非解析管道）
    if (isArchiveContainer(doc)) {
      String cStatus = normalizeStatus(doc.getParseStatus());
      if ("PENDING".equals(cStatus) || "EXPANDING".equals(cStatus)) {
        throw new BizException(409, "ALREADY_IN_PROGRESS");
      }
      int reset = documentMapper.resetContainerForRetry(id);
      if (reset == 0) {
        throw new BizException(409, "REPROCESS_NOT_ALLOWED");
      }
      dispatchArchiveExpandAfterCommit(id);
      return Map.of("documentId", id, "status", "PENDING");
    }

    String status = normalizeStatus(doc.getParseStatus());
    if ("PENDING".equals(status) || "PROCESSING".equals(status) || "REPROCESSING".equals(status)) {
      throw new BizException(409, "ALREADY_IN_PROGRESS");
    }
    if (!"FAILED".equals(status) && !"COMPLETED".equals(status)) {
      throw new BizException(409, "REPROCESS_NOT_ALLOWED");
    }

    documentMapper.updateStatus(id, "PENDING");
    sendProcessAfterCommit(id);
    return Map.of("documentId", id, "status", "PENDING");
  }

  private static final java.util.Set<String> ARCHIVE_FILE_TYPES = java.util.Set.of("zip", "tar.gz");

  private boolean isArchiveContainer(Document doc) {
    return doc.getParentDocumentId() == null
        && doc.getFileType() != null
        && ARCHIVE_FILE_TYPES.contains(doc.getFileType());
  }

  private void dispatchArchiveExpandAfterCommit(Long id) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      archiveExpandProducer.send(id);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            archiveExpandProducer.send(id);
          }
        });
  }

  @Override
  @Transactional
  public Map<String, Object> rechunk(Long id, RechunkRequest request) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "DOCUMENT_NOT_FOUND");
    }
    if (!kbAccessGuard.canWrite(doc.getKbId())) {
      throw new BizException(403, "KB_WRITE_FORBIDDEN");
    }

    String status = normalizeStatus(doc.getParseStatus());
    if ("PENDING".equals(status) || "PROCESSING".equals(status) || "REPROCESSING".equals(status)) {
      throw new BizException(409, "ALREADY_IN_PROGRESS");
    }

    List<DocumentChunk> existingChunks =
        documentChunkMapper.selectList(
            new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, id));
    String oldStrategy = RechunkSupport.resolveOldStrategy(existingChunks);

    if (RechunkSupport.isImageOnlyDocument(doc, existingChunks)) {
      documentMapper.updateRechunkRequest(id, null, null, "REPROCESSING");
      sendProcessAfterCommit(id);
      return Map.of(
          "documentId",
          id,
          "status",
          "REPROCESSING",
          "oldStrategy",
          oldStrategy,
          "newStrategy",
          "IMAGE_PIPELINE");
    }

    int cleanedTextLength = RechunkSupport.resolveCleanedTextLength(doc, objectMapper);
    RechunkSupport.validateRequest(request, cleanedTextLength);

    String newStrategy = resolveNewStrategy(doc.getKbId(), request);
    String storedStrategy =
        request != null && StringUtils.hasText(request.getStrategy())
            ? RechunkSupport.normalizeStrategy(request.getStrategy())
            : null;
    String paramsJson = buildRechunkParamsJson(request, doc.getKbId());

    documentMapper.updateRechunkRequest(id, storedStrategy, paramsJson, "REPROCESSING");
    sendProcessAfterCommit(id);
    return Map.of(
        "documentId",
        id,
        "status",
        "REPROCESSING",
        "oldStrategy",
        oldStrategy,
        "newStrategy",
        newStrategy);
  }

  private String resolveNewStrategy(Long kbId, RechunkRequest request) {
    if (request != null && StringUtils.hasText(request.getStrategy())) {
      return RechunkSupport.normalizeStrategy(request.getStrategy());
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    ChunkerProfile profile = chunkingService.resolveProfile(kb);
    return profile.getDefaultStrategy() == null ? "MARKDOWN_HEADING" : profile.getDefaultStrategy();
  }

  private String buildRechunkParamsJson(RechunkRequest request, Long kbId) {
    if (request == null || !StringUtils.hasText(request.getStrategy())) {
      return null;
    }
    String strategy = RechunkSupport.normalizeStrategy(request.getStrategy());
    if (!RechunkSupport.usesFixedParams(strategy)) {
      return null;
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    ChunkerProfile profile = chunkingService.resolveProfile(kb);
    ChunkParams params = RechunkSupport.toChunkParams(request, profile.getParams());
    try {
      return objectMapper.writeValueAsString(params);
    } catch (Exception e) {
      throw new BizException(400, "INVALID_CHUNKER_PROFILE_JSON");
    }
  }

  private void applyFileMd5(IngestCommand cmd, String fileBytesMd5) {
    cmd.setFileBytesMd5(fileBytesMd5);
    if (cmd.getIdentity() == null) {
      cmd.setIdentity(new Identity());
    }
    if (cmd.getIdentity().getContentMd5() == null || cmd.getIdentity().getContentMd5().isBlank()) {
      cmd.getIdentity().setContentMd5(fileBytesMd5);
    }
  }

  private Map<String, Object> ingestResponse(IngestResult result) {
    return Map.of("documentId", result.getDocumentId(), "status", result.getStatus().name());
  }

  private void sendProcessAfterCommit(Long documentId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      mqProducer.send(documentId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            mqProducer.send(documentId);
          }
        });
  }

  private String normalizeStatus(String status) {
    return status == null ? "" : status.trim().toUpperCase();
  }

  private IngestCommand parseMeta(String metaJson) {
    try {
      return objectMapper.readValue(metaJson, IngestCommand.class);
    } catch (Exception e) {
      throw new BizException(400, "INVALID_UPLOAD_META");
    }
  }

  private String storageKey(Long kbId, String filename) {
    return "kb_" + kbId + "/" + UUID.randomUUID() + "/" + filename;
  }

  /** 从 OSS 对象读取文件头，按 magic number 判定压缩格式（读取失败按非压缩包处理，不阻断上传）。 */
  private ArchiveFormat detectArchiveFormat(String bucket, String key) {
    try (InputStream in = objectStorage.get(bucket, key)) {
      byte[] header = in.readNBytes(ArchiveFormatDetector.HEADER_BYTES);
      return archiveFormatDetector.detect(header);
    } catch (Exception e) {
      log.warn("archive magic detect failed bucket={} key={} err={}", bucket, key, e.getMessage());
      return ArchiveFormat.UNKNOWN;
    }
  }

  /** 从本地临时文件读取文件头，按 magic number 判定压缩格式（relay 通道用）。 */
  private ArchiveFormat detectArchiveFormatFromFile(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      byte[] header = in.readNBytes(ArchiveFormatDetector.HEADER_BYTES);
      return archiveFormatDetector.detect(header);
    } catch (Exception e) {
      log.warn("archive magic detect (relay) failed err={}", e.getMessage());
      return ArchiveFormat.UNKNOWN;
    }
  }

  private String defaultBucket(String requestedBucket) {
    if (requestedBucket != null && !requestedBucket.isBlank()) {
      return requestedBucket;
    }
    String bucket = storageProperties.getAliyun().getBucket();
    return bucket == null || bucket.isBlank() ? "local" : bucket;
  }

  private void safeDelete(String bucket, String key) {
    try {
      objectStorage.delete(bucket, key);
    } catch (Exception ignored) {
      // 上传登记失败后的对象清理是 best-effort，不能掩盖主错误。
    }
  }

  private static String cleanFilename(String originalFilename) {
    if (originalFilename == null || originalFilename.isBlank()) {
      return "upload.bin";
    }
    String cleaned = originalFilename.replace('\\', '/');
    int slash = cleaned.lastIndexOf('/');
    if (slash >= 0) {
      cleaned = cleaned.substring(slash + 1);
    }
    return cleaned.replaceAll("[\\r\\n]", "_");
  }

  private static String cleanPathPart(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

}
