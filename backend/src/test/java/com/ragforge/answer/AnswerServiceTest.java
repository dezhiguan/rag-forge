package com.ragforge.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.common.BizException;
import com.ragforge.judge.sampler.AnswerJudgeProducer;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.pipeline.cleaner.L3PiiMaskCleaner;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.service.LlmService;
import com.ragforge.storage.ObjectStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

  @Mock private RetrievalService retrievalService;
  @Mock private LlmService llmService;
  @Mock private AnswerLogMapper answerLogMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private ObjectStorage objectStorage;
  @Mock private AnswerJudgeProducer answerJudgeProducer;
  @Mock private com.ragforge.modelcenter.ModelResolver modelResolver;
  @Mock private com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder;

  private AnswerService answerService;

  @BeforeEach
  void setUp() {
    answerService =
        new AnswerService(
            retrievalService,
            new PromptBuilder(),
            llmService,
            new CitationLinker(documentMapper, objectStorage),
            new GuardRails(),
            answerLogMapper,
            knowledgeBaseMapper,
            new ObjectMapper(),
            new L3PiiMaskCleaner(),
            new RagforgeMetrics(new SimpleMeterRegistry()),
            answerJudgeProducer,
            modelResolver,
            modelUsageRecorder);
    lenient()
        .when(modelResolver.resolveCodeOrDefault(any(), any()))
        .thenReturn("qwen-plus");
    lenient().when(answerLogMapper.insertAnswerLog(any())).thenAnswer(
        invocation -> {
          AnswerLog log = invocation.getArgument(0);
          log.setId(100L);
          return 1;
        });
  }

  @Test
  void normalFlow_linksCitations() {
    mockKb("ON");
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"), hit(2, "TEXT"), hit(3, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("广州 Java 常见 Spring Boot[1] 和 Redis[2]", 100, 20, 50));

    AnswerResponse response = answerService.answerBlocking(request());

    assertThat(response.getCitations()).hasSize(2);
    assertThat(response.getCitations()).extracting("id").containsExactly(1, 2);
    assertThat(response.getGuardRailResult()).isEqualTo("PASS");
  }

  @Test
  void imageChunkCitation_hasPresignedImageUrl() {
    mockKb("ON");
    SearchResult image = hit(2, "IMAGE");
    image.setImageKey("images/doc-2/page-1.png");
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"), image, hit(3, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("架构图显示服务依赖关系[2]", 100, 20, 50));
    Document doc = new Document();
    doc.setStorageBucket("bucket-a");
    doc.setStorageKey("docs/original.pdf");
    when(documentMapper.selectById(2L)).thenReturn(doc);
    when(objectStorage.presignedGet(eq("bucket-a"), eq("images/doc-2/page-1.png"), any(Duration.class)))
        .thenReturn("https://oss.example/image.png?sig=1");

    AnswerResponse response = answerService.answerBlocking(request());

    assertThat(response.getCitations()).hasSize(1);
    assertThat(response.getCitations().get(0).getImageUrl()).isEqualTo("https://oss.example/image.png?sig=1");
    verify(objectStorage).presignedGet(eq("bucket-a"), eq("images/doc-2/page-1.png"), any(Duration.class));
  }

  @Test
  void noCitations_throws422() {
    mockKb("ON");
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("广州 Java 常见 Spring Boot", 100, 20, 50));

    assertThatThrownBy(() -> answerService.answerBlocking(request()))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(422);
  }

  @Test
  void piiLeak_throws422() {
    mockKb("ON");
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("联系人手机号 13812345678[1]", 100, 20, 50));

    assertThatThrownBy(() -> answerService.answerBlocking(request()))
        .isInstanceOf(BizException.class)
        .hasMessage("PII_LEAK");
  }

  @Test
  void answerContainingKnowledgeBasePhraseWithCitation_passes() {
    mockKb("ON");
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("根据我的知识库，广州 Java 岗位常见 Spring Boot 技术栈[1]", 100, 20, 50));

    AnswerResponse response = answerService.answerBlocking(request());

    assertThat(response.getGuardRailResult()).isEqualTo("PASS");
    assertThat(response.getCitations()).hasSize(1);
  }

  @Test
  void answerBlocking_publishFailureDoesNotAffectAnswer() {
    mockKb("ON");
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("广州 Java 常见 Spring Boot[1]", 100, 20, 50));
    org.mockito.Mockito.doThrow(new RuntimeException("mq down"))
        .when(answerJudgeProducer)
        .publishJudgeRequest(any(), any());

    AnswerResponse response = answerService.answerBlocking(request());

    assertThat(response.getGuardRailResult()).isEqualTo("PASS");
  }

  @Test
  void missingKbAnswerModel_fallsBackToQwenPlus() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(16L);
    kb.setAnswerMode("ON");
    kb.setAnswerModel(null);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb));
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("hybrid"), eq(0.7), eq(10), eq(10), any()))
        .thenReturn(output(List.of(hit(1, "TEXT"))));
    when(llmService.streamGenerate(any(LlmGenerateRequest.class), anyInt(), any()))
        .thenReturn(new LlmService.StreamResult("广州 Java 常见 Spring Boot[1]", 100, 20, 50));

    AnswerResponse response = answerService.answerBlocking(request());

    assertThat(response.getLlmModel()).isEqualTo("qwen-plus");
    verify(llmService).streamGenerate(argThat(req -> "qwen-plus".equals(req.getModel())), anyInt(), any());
  }

  @Test
  void safeResultsForStream_masksPiiButKeepsOriginalRetrievalContent() {
    SearchResult source = hit(1, "TEXT");
    source.setContent("联系人手机号 13812345678，邮箱 boss@example.com");
    RetrievalOutput output = output(List.of(source));

    List<SearchResult> safe = answerService.safeResultsForStream(output);

    assertThat(safe).hasSize(1);
    assertThat(safe.get(0).getContent()).contains("138****5678").contains("b***@example.com");
    assertThat(safe.get(0).getContent()).doesNotContain("13812345678", "boss@example.com");
    assertThat(source.getContent()).contains("13812345678", "boss@example.com");
  }

  @Test
  void answerModeOff_throws403() {
    mockKb("OFF");

    assertThatThrownBy(() -> answerService.answerBlocking(request()))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(403);
  }

  private void mockKb(String mode) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(16L);
    kb.setAnswerMode(mode);
    kb.setAnswerModel("qwen-max");
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb));
  }

  private AnswerRequest request() {
    AnswerRequest request = new AnswerRequest();
    request.setKbIds(List.of(16L));
    request.setQuery("广州 Java 25-30k 技术栈");
    request.setRetrievalStrategy("hybrid");
    request.setAnswerMode("ON");
    request.setTopK(10);
    request.setMaxTokens(800);
    return request;
  }

  private RetrievalOutput output(List<SearchResult> hits) {
    return new RetrievalOutput(hits, 30, "hybrid", null, null, 10L, 20L, null);
  }

  private SearchResult hit(long id, String modality) {
    SearchResult result = new SearchResult();
    result.setChunkId(id);
    result.setDocId(id);
    result.setFilename("doc-" + id + ".md");
    result.setContent("chunk-" + id + " content");
    result.setChunkModality(modality);
    result.setFinalScore(0.9);
    return result;
  }
}
