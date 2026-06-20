package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.common.PageResult;
import com.ragforge.model.dto.TextUploadRequest;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.service.DocumentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

  @Mock private DocumentService documentService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        standaloneSetup(new DocumentController(documentService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(
                new ResourceHttpMessageConverter(), new MappingJackson2HttpMessageConverter())
            .setValidator(validator)
            .build();
  }

  @Test
  void upload_delegatesToService() throws Exception {
    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setDocId(10L);
    when(documentService.upload(eq(1L), any(), eq(false))).thenReturn(result);

    MockMultipartFile file =
        new MockMultipartFile("file", "readme.txt", "text/plain", "hello".getBytes());

    mockMvc
        .perform(multipart("/api/v1/kb/1/documents").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.docId").value(10));
  }

  @Test
  void uploadAlias_acceptsKbIdQueryParam() throws Exception {
    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setDocId(11L);
    when(documentService.upload(eq(2L), any(), eq(true))).thenReturn(result);

    MockMultipartFile file =
        new MockMultipartFile("file", "a.txt", "text/plain", "data".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/documents/upload")
                .file(file)
                .param("kbId", "2")
                .param("overwrite", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.docId").value(11));
  }

  @Test
  void listByKb_returnsPagedDocuments() throws Exception {
    DocumentVO doc = new DocumentVO();
    doc.setId(5L);
    when(documentService.listByKb(1L, 1, 20)).thenReturn(PageResult.of(1, 1, 20, List.of(doc)));

    mockMvc
        .perform(get("/api/v1/kb/1/documents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].id").value(5));
  }

  @Test
  void getById_returnsDocumentDetail() throws Exception {
    DocumentDetailVO detail = new DocumentDetailVO();
    detail.setId(7L);
    when(documentService.getById(7L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/documents/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(7));
  }

  @Test
  void listChunks_returnsPagedChunks() throws Exception {
    DocumentChunkVO chunk = new DocumentChunkVO();
    chunk.setChunkIndex(0);
    chunk.setContent("chunk text");
    when(documentService.listChunks(8L, 1, 20))
        .thenReturn(PageResult.of(1, 1, 20, List.of(chunk)));

    mockMvc
        .perform(get("/api/v1/documents/8/chunks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].chunkIndex").value(0));
  }

  @Test
  void getStatus_returnsDocumentStatus() throws Exception {
    DocumentStatusVO status = new DocumentStatusVO();
    status.setParseStatus("completed");
    status.setChunkCount(3);
    when(documentService.getStatus(9L)).thenReturn(status);

    mockMvc
        .perform(get("/api/v1/documents/9/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.parseStatus").value("completed"));
  }

  @Test
  void download_delegatesToService() throws Exception {
    when(documentService.download(12L))
        .thenReturn(ResponseEntity.ok(new ByteArrayResource("file".getBytes())));

    mockMvc.perform(get("/api/v1/documents/12/download")).andExpect(status().isOk());
  }

  @Test
  void deleteAndReprocess_delegateToService() throws Exception {
    mockMvc.perform(delete("/api/v1/documents/13")).andExpect(status().isOk());
    mockMvc.perform(post("/api/v1/documents/13/reprocess")).andExpect(status().isOk());

    verify(documentService).delete(13L);
    verify(documentService).reprocess(13L);
  }

  @Test
  void uploadText_acceptsJsonBody() throws Exception {
    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setDocId(20L);
    when(documentService.uploadText(any(TextUploadRequest.class))).thenReturn(result);

    mockMvc
        .perform(
            post("/api/v1/documents/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kbId":1,"title":"note.txt","content":"hello world","chunkType":"GENERAL"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.docId").value(20));
  }
}
