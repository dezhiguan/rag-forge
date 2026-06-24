package com.ragforge.document.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.common.RelayUploadLimits;
import com.ragforge.document.dto.UploadRelayResult;
import com.ragforge.document.service.DocumentUploadApplicationService;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.PresignUploadRequest;
import com.ragforge.model.dto.RegisterUploadRequest;
import com.ragforge.model.entity.Document;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentUploadApplicationServiceImpl implements DocumentUploadApplicationService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final IngestService ingestService;
  private final ObjectStorage objectStorage;
  private final StorageProperties storageProperties;
  private final KbAccessGuard kbAccessGuard;
  private final ObjectMapper objectMapper;
  private final UploadTokenService uploadTokenService;
  private final DocumentMapper documentMapper;
  private final DocumentProcessProducer mqProducer;

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
    String tokenTenantId = currentTenantId();
    String uploadId = randomUploadId();
    String key =
        cleanPathPart(tokenTenantId)
            + "/kb_"
            + request.getKbId()
            + "/"
            + uploadId
            + "/"
            + filename;

    TokenPayload payload = new TokenPayload();
    payload.setTenantId(tokenTenantId);
    payload.setKbId(request.getKbId());
    payload.setStorageBucket(bucket);
    payload.setStorageKey(key);
    payload.setFilename(filename);
    payload.setContentType(request.getContentType());
    payload.setDeclaredSize(request.getDeclaredSize());
    String token = uploadTokenService.issue(payload);

    ObjectMeta meta = new ObjectMeta();
    meta.setContentType(request.getContentType());
    meta.setSizeBytes(request.getDeclaredSize());
    meta.setUserMeta(Map.of("kb-id", String.valueOf(request.getKbId()), "tenant-id", tokenTenantId));
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
    TokenPayload payload = uploadTokenService.consume(request.getUploadToken());
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null || !payload.getTenantId().equals(currentTenantId())) {
      throw new BizException(403, "UPLOAD_TOKEN_TENANT_FORBIDDEN");
    }
    if (!payload.getKbId().equals(request.getKbId())) {
      throw new BizException(403, "UPLOAD_TOKEN_KB_FORBIDDEN");
    }

    ObjectMeta head = objectStorage.head(payload.getStorageBucket(), payload.getStorageKey());
    if (head == null) {
      throw new BizException(422, "UPLOAD_NOT_FOUND");
    }
    if (head.getSizeBytes() == null || !head.getSizeBytes().equals(payload.getDeclaredSize())) {
      throw new BizException(422, "SIZE_MISMATCH");
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

    IngestResult result = ingestService.register(cmd);
    if (result.getStatus() == IngestResult.Status.SKIPPED) {
      safeDelete(payload.getStorageBucket(), payload.getStorageKey());
    }
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

  @Override
  @Transactional
  public Map<String, Object> rechunk(Long id) {
    Document doc = documentMapper.selectById(id);
    if (doc == null) {
      throw new BizException(404, "DOCUMENT_NOT_FOUND");
    }

    String status = normalizeStatus(doc.getParseStatus());
    if ("PENDING".equals(status) || "PROCESSING".equals(status) || "REPROCESSING".equals(status)) {
      throw new BizException(409, "ALREADY_IN_PROGRESS");
    }

    documentMapper.updateStatus(id, "REPROCESSING");
    sendProcessAfterCommit(id);
    return Map.of("documentId", id, "status", "REPROCESSING");
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
    String tenantId = currentTenantId();
    return cleanPathPart(tenantId) + "/" + kbId + "/" + UUID.randomUUID() + "/" + filename;
  }

  private String currentTenantId() {
    RagAuthContext context = RagAuthContextHolder.get();
    return context == null || context.tenantId() == null ? "default" : context.tenantId();
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

  private static String randomUploadId() {
    byte[] bytes = new byte[16];
    SECURE_RANDOM.nextBytes(bytes);
    return "uplt_" + HexFormat.of().formatHex(bytes);
  }
}
