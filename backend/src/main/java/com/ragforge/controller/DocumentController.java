package com.ragforge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.common.PageResult;
import com.ragforge.common.Result;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.PresignUploadRequest;
import com.ragforge.model.dto.RegisterUploadRequest;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.DocumentService;
import com.ragforge.service.ingest.IngestService;
import com.ragforge.service.upload.UploadTokenService;
import com.ragforge.service.upload.UploadTokenService.TokenPayload;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.storage.StorageProperties;
import jakarta.validation.constraints.Min;
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
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

  private static final long RELAY_UPLOAD_LIMIT_BYTES = 50L * 1024L * 1024L;
  private static final int RELAY_UPLOAD_LIMIT_MB = 50;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final DocumentService documentService;
  private final IngestService ingestService;
  private final ObjectStorage objectStorage;
  private final StorageProperties storageProperties;
  private final KbAccessGuard kbAccessGuard;
  private final ObjectMapper objectMapper;
  private final UploadTokenService uploadTokenService;

  @PostMapping("/uploads/presign")
  public ResponseEntity<?> presignUpload(@RequestBody PresignUploadRequest request) {
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
        objectStorage.presignedPut(bucket, key, UploadTokenService.TOKEN_TTL, meta);

    return ResponseEntity.ok(
        Map.of(
            "uploadToken",
            token,
            "presignedPutUrl",
            presignedPutUrl,
            "storageBucket",
            bucket,
            "storageKey",
            key,
            "expiresAt",
            Instant.ofEpochSecond(payload.getExpiresAt()).toString()));
  }

  @PostMapping("/documents/register")
  public ResponseEntity<?> registerUploadedDocument(@RequestBody RegisterUploadRequest request) {
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
    return ResponseEntity.ok(
        Map.of("documentId", result.getDocumentId(), "status", result.getStatus().name()));
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> uploadV5(
      @RequestParam("file") MultipartFile file, @RequestParam("meta") String metaJson) {
    IngestCommand cmd = parseMeta(metaJson);
    Long kbId = cmd.getKbId();
    if (!kbAccessGuard.canWrite(kbId)) {
      throw new BizException(403, "KB_WRITE_FORBIDDEN");
    }
    if (file.getSize() > RELAY_UPLOAD_LIMIT_BYTES) {
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
          .body(
              Map.of(
                  "error",
                  "FILE_TOO_LARGE_FOR_RELAY",
                  "presignUrl",
                  "/api/v1/uploads/presign",
                  "limitMb",
                  RELAY_UPLOAD_LIMIT_MB));
    }

    Path tempFile = null;
    String bucket = defaultBucket(cmd.getStorageBucket());
    String key = null;
    try {
      tempFile = Files.createTempFile("ragforge-upload-", ".bin");
      MessageDigest digest = MessageDigest.getInstance("MD5");
      try (InputStream raw = file.getInputStream();
          DigestInputStream in = new DigestInputStream(raw, digest);
          var out = Files.newOutputStream(tempFile)) {
        in.transferTo(out);
      }
      String contentMd5 = HexFormat.of().formatHex(digest.digest());
      if (cmd.getIdentity() == null) {
        cmd.setIdentity(new Identity());
      }
      cmd.getIdentity().setContentMd5(contentMd5);

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
      objectMeta.setUserMeta(Map.of("content-md5", contentMd5, "kb-id", String.valueOf(kbId)));
      try (InputStream in = Files.newInputStream(tempFile)) {
        objectStorage.put(bucket, key, in, objectMeta);
      }

      IngestResult result = ingestService.register(cmd);
      return ResponseEntity.ok(
          Map.of("documentId", result.getDocumentId(), "status", result.getStatus().name()));
    } catch (BizException e) {
      if (key != null && e.getCode() == 409) {
        safeDelete(bucket, key);
      }
      if (e.getCode() == 409 && "DOC_IDENTITY_CONFLICT".equals(e.getMessage())) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "DOC_IDENTITY_CONFLICT", "existingDocId", ""));
      }
      throw e;
    } catch (Exception e) {
      if (key != null) {
        safeDelete(bucket, key);
      }
      throw new BizException(500, "DOCUMENT_UPLOAD_FAILED: " + e.getMessage());
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

  @PostMapping("/kb/{kbId}/documents")
  @PreAuthorize("@kbAccessGuard.canWrite(#kbId)")
  public Result<DocumentUploadResultVO> upload(
      @PathVariable Long kbId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
    return Result.ok(documentService.upload(kbId, file, overwrite));
  }

  /**
   * Backward/forward compatible alias for docs.
   *
   * <p>Alias route: /api/v1/documents/upload?kbId=1&overwrite=true
   */
  @PostMapping("/documents/upload")
  @PreAuthorize("@kbAccessGuard.canWrite(#kbId)")
  public Result<DocumentUploadResultVO> uploadAlias(
      @RequestParam("kbId") Long kbId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
    return Result.ok(documentService.upload(kbId, file, overwrite));
  }

  @PostMapping("/kb/{kbId}/documents/replace/{docId}")
  @PreAuthorize("@kbAccessGuard.canWrite(#kbId)")
  public Result<DocumentVO> replace(
      @PathVariable Long kbId,
      @PathVariable Long docId,
      @RequestParam("file") MultipartFile file) {
    return Result.ok(documentService.replaceDocument(kbId, file, docId));
  }

  @GetMapping("/kb/{kbId}/documents")
  @PreAuthorize("@kbAccessGuard.canRead(#kbId)")
  public Result<PageResult<DocumentVO>> listByKb(
      @PathVariable Long kbId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size) {
    return Result.ok(documentService.listByKb(kbId, page, size));
  }

  @GetMapping("/documents/{id}")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<DocumentDetailVO> getById(@PathVariable Long id) {
    return Result.ok(documentService.getById(id));
  }

  @GetMapping("/documents/{id}/chunks")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<PageResult<DocumentChunkVO>> listChunks(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) int size) {
    return Result.ok(documentService.listChunks(id, page, size));
  }

  @GetMapping("/documents/{id}/status")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public Result<DocumentStatusVO> getStatus(@PathVariable Long id) {
    return Result.ok(documentService.getStatus(id));
  }

  @GetMapping("/documents/{id}/download")
  @PreAuthorize("@kbAccessGuard.canReadDocument(#id)")
  public ResponseEntity<Resource> download(@PathVariable Long id) {
    return documentService.download(id);
  }

  @DeleteMapping("/documents/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR') and @kbAccessGuard.canWriteDocument(#id)")
  public Result<Void> delete(@PathVariable Long id) {
    documentService.delete(id);
    return Result.ok();
  }

  @PostMapping("/documents/{id}/reprocess")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR') and @kbAccessGuard.canWriteDocument(#id)")
  public Result<Void> reprocess(@PathVariable Long id) {
    documentService.reprocess(id);
    return Result.ok();
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
