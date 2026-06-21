package com.ragforge.document.service;

import com.ragforge.document.dto.UploadRelayResult;
import com.ragforge.model.dto.PresignUploadRequest;
import com.ragforge.model.dto.RegisterUploadRequest;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentUploadApplicationService {

  Map<String, Object> presignUpload(PresignUploadRequest request);

  Map<String, Object> registerUploadedDocument(RegisterUploadRequest request);

  UploadRelayResult relayUpload(MultipartFile file, String metaJson);

  Map<String, Object> reprocess(Long id);

  Map<String, Object> rechunk(Long id);
}
