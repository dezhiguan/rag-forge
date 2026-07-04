package com.ragforge.answer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.answer.AnswerModels.AnswerRun;
import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.answer.AnswerModels.GuardRailResult;
import com.ragforge.answer.AnswerModels.Latency;
import com.ragforge.answer.AnswerModels.TokenUsage;
import com.ragforge.common.BizException;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.modelcenter.ModelResolver;
import com.ragforge.modelcenter.ModelUsageEvent;
import com.ragforge.modelcenter.ModelUsageRecorder;
import com.ragforge.modelcenter.Purpose;
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.pipeline.cleaner.L3PiiMaskCleaner;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.judge.sampler.AnswerJudgeMessage;
import com.ragforge.judge.sampler.AnswerJudgeProducer;
import com.ragforge.judge.sampler.SampleRequest;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.LlmService;
import com.ragforge.storage.ChunkImageResolver;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.web.TraceIds;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerService {

  private static final String NOT_FOUND_ANSWER = "未在知识库中找到相关内容";

  private final RetrievalService retrievalService;
  private final PromptBuilder promptBuilder;
  private final LlmService llmService;
  private final CitationLinker citationLinker;
  private final GuardRails guardRails;
  private final AnswerLogMapper answerLogMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final ObjectMapper objectMapper;
  private final L3PiiMaskCleaner piiMaskCleaner;
  private final RagforgeMetrics metrics;
  private final AnswerJudgeProducer answerJudgeProducer;
  private final ModelResolver modelResolver;
  private final ModelUsageRecorder modelUsageRecorder;
  // 应答流式专用有界池（M4）：按 bean 名 answerExecutor 注入，取代 commonPool。
  private final Executor answerExecutor;

  public SseEmitter answer(AnswerRequest request) {
    validateRequest(request);
    RagAuthContext authContext = RagAuthContextHolder.get();
    SseEmitter emitter = new SseEmitter(600_000L);
    try {
      // 专用有界池(M4)，取代无界的 ForkJoinPool.commonPool；池满即拒绝，转成友好提示而非 500/无限积压。
      CompletableFuture.runAsync(
          () -> {
            try {
              RagAuthContextHolder.set(authContext);
              answerInternal(request, delta -> send(emitter, "token", Map.of("delta", delta)), emitter);
            } catch (BizException e) {
              sendError(emitter, e.getMessage(), e.getMessage());
            } catch (Exception e) {
              log.error("answer stream failed", e);
              sendError(emitter, "ANSWER_FAILED", e.getMessage());
            } finally {
              RagAuthContextHolder.clear();
            }
          },
          answerExecutor);
    } catch (RejectedExecutionException e) {
      log.warn("answer executor saturated, rejecting request");
      sendError(emitter, "ANSWER_BUSY", "服务繁忙，请稍后重试");
    }
    return emitter;
  }

  public void validateAnswerMode(AnswerRequest request) {
    validateRequest(request);
    enforceAnswerMode(loadKnowledgeBases(distinct(request.getKbIds())), request.getAnswerMode());
  }

  public AnswerResponse answerSync(AnswerRequest request) {
    validateRequest(request);
    return answerInternal(request, null, null).response();
  }

  public AnswerResponse answerBlocking(AnswerRequest request) {
    validateRequest(request);
    return answerInternal(request, null, null).response();
  }

  private AnswerRun answerInternal(AnswerRequest request, TokenSink tokenSink, SseEmitter emitter) {
    long start = System.currentTimeMillis();
    List<Long> kbIds = distinct(request.getKbIds());
    List<KnowledgeBase> kbs = loadKnowledgeBases(kbIds);
    // Golden Set 评测回放是内部质量评估，不应受知识库“对外应答开关(answerMode=OFF)”限制，
    // 否则默认关闭应答的知识库将永远无法产出 judge 评测数据。仅评测回放绕过该守卫。
    String effectiveMode =
        isGoldenSetReplay(request)
            ? normalizeMode(textOrDefault(request.getAnswerMode(), "ON"))
            : enforceAnswerMode(kbs, request.getAnswerMode());
    String llmModel = selectModel(kbs);

    RetrievalOutput retrieval =
        retrievalService.retrieve(
            request.getQuery(),
            kbIds,
            null,
            textOrDefault(request.getRetrievalStrategy(), "hybrid"),
            0.7,
            clamp(request.getTopK(), 1, 30),
            clamp(request.getTopK(), 1, 30),
            null);
    SearchResponse retrievalResponse = toSearchResponse(retrieval);
    if (emitter != null) {
      send(
          emitter,
          "retrieval",
          Map.of("chunks", safeResultsForStream(retrieval), "latencyMs", retrieval.getLatencyMs()));
    }

    if (retrieval.getResults() == null || retrieval.getResults().isEmpty()) {
      AnswerResponse response = new AnswerResponse();
      response.setAnswer(NOT_FOUND_ANSWER);
      response.setCitations(List.of());
      response.setRetrieval(retrievalResponse);
      response.setTokens(new TokenUsage(0, 0));
      response.setLatency(new Latency(retrieval.getLatencyMs(), 0, System.currentTimeMillis() - start));
      response.setGuardRailResult(GuardRailResult.PASS.name());
      response.setLlmModel(llmModel);
      metrics.updateAnswerCitationRate(0, 0);
      writeLog(
          request, kbIds, response, retrieval.getResults(), effectiveMode, llmModel, retrieval.getStrategy(), start);
      if (emitter != null) {
        send(emitter, "token", Map.of("delta", NOT_FOUND_ANSWER));
        send(emitter, "complete", response);
        emitter.complete();
      }
      return new AnswerRun(response, effectiveMode, llmModel, retrieval.getStrategy(), retrieval.getLatencyMs(), 0, response.getLatency().total());
    }

    String prompt = promptBuilder.build(request.getQuery(), retrieval.getResults(), kbs.get(0));
    LlmGenerateRequest llmRequest = new LlmGenerateRequest();
    llmRequest.setModel(llmModel);
    llmRequest.setTemperature(0.2);
    llmRequest.setMessages(List.of(Map.of("role", "user", "content", prompt)));
    long llmStart = System.currentTimeMillis();
    LlmService.StreamResult llmResult =
        llmService.streamGenerate(
            llmRequest,
            clamp(request.getMaxTokens(), 64, 4000),
            delta -> {
              if (tokenSink != null) {
                tokenSink.accept(delta);
              }
            });
    long llmLatency = llmResult.latencyMs() > 0 ? llmResult.latencyMs() : System.currentTimeMillis() - llmStart;
    // 规范化答案里的 [n] 引用编号:应答 LLM 常把编号写飘(如只检索到 1 个块却引用 [5]),
    // 越界编号收敛到有效范围,避免用户看到指向不存在块的悬空编号(与检索块显示对齐)。
    String answerText =
        CitationLinker.normalizeCitationMarkers(llmResult.content(), retrieval.getResults().size());
    List<Citation> citations = citationLinker.link(answerText, retrieval.getResults());
    GuardRailResult guard = guardRails.check(answerText, citations);

    AnswerResponse response = new AnswerResponse();
    response.setAnswer(answerText);
    response.setCitations(citations);
    response.setRetrieval(retrievalResponse);
    response.setTokens(new TokenUsage(llmResult.promptTokens(), llmResult.completionTokens()));
    response.setLatency(new Latency(retrieval.getLatencyMs(), llmLatency, System.currentTimeMillis() - start));
    response.setGuardRailResult(guard.name());
    response.setLlmModel(llmModel);
    metrics.recordAnswerTokens(llmResult.promptTokens(), llmResult.completionTokens());
    modelUsageRecorder.record(
        new ModelUsageEvent(
            llmModel,
            Purpose.ANSWER,
            llmResult.promptTokens(),
            llmResult.completionTokens(),
            llmLatency,
            true));
    metrics.updateAnswerCitationRate(citations.size(), retrieval.getResults().size());
    if (guard != GuardRailResult.PASS) {
      metrics.recordAnswerGuardRailBlocked(guard.name());
    }
    writeLog(
        request, kbIds, response, retrieval.getResults(), effectiveMode, llmModel, retrieval.getStrategy(), start);

    if (guard != GuardRailResult.PASS) {
      if (emitter != null) {
        sendError(emitter, guard.name(), guardMessage(guard));
        return new AnswerRun(response, effectiveMode, llmModel, retrieval.getStrategy(), retrieval.getLatencyMs(), llmLatency, response.getLatency().total());
      }
      throw new BizException(422, guard.name());
    }
    if (emitter != null) {
      send(emitter, "complete", response);
      emitter.complete();
    }
    return new AnswerRun(response, effectiveMode, llmModel, retrieval.getStrategy(), retrieval.getLatencyMs(), llmLatency, response.getLatency().total());
  }

  private List<KnowledgeBase> loadKnowledgeBases(List<Long> kbIds) {
    List<KnowledgeBase> kbs =
        knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>().in(KnowledgeBase::getId, kbIds));
    if (kbs == null || kbs.size() != kbIds.size()) {
      throw new BizException(404, "KB_NOT_FOUND");
    }
    return kbs;
  }

  private String enforceAnswerMode(List<KnowledgeBase> kbs, String requestedMode) {
    String requestMode = normalizeMode(textOrDefault(requestedMode, "ON"));
    for (KnowledgeBase kb : kbs) {
      String kbMode = normalizeMode(kb.getAnswerMode());
      if ("OFF".equals(kbMode)) {
        throw new BizException(403, "ANSWER_DISABLED");
      }
      if ("ON".equals(requestMode) && !"ON".equals(kbMode)) {
        throw new BizException(403, "ANSWER_DISABLED");
      }
    }
    return requestMode;
  }

  private String selectModel(List<KnowledgeBase> kbs) {
    return kbs.stream()
        .map(KnowledgeBase::getAnswerModel)
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        // KB 未显式指定时，全局默认从模型注册表动态解析（无可用模型则回退 qwen-plus）
        .orElseGet(() -> modelResolver.resolveCodeOrDefault(Purpose.ANSWER, "qwen-plus"));
  }

  private void writeLog(
      AnswerRequest request,
      List<Long> kbIds,
      AnswerResponse response,
      List<SearchResult> retrievedChunks,
      String answerMode,
      String llmModel,
      String retrievalStrategy,
      long start) {
    try {
      RagAuthContext auth = RagAuthContextHolder.get();
      AnswerLog log = new AnswerLog();
      log.setTenantId("default"); // 租户模型已移除，保留列写默认值（待迁移删列）
      log.setPrincipalId(auth == null ? null : auth.principalId());
      log.setKbIdsCsv(String.join(",", kbIds.stream().map(String::valueOf).toList()));
      log.setQuery(request.getQuery());
      log.setAnswer(response.getAnswer());
      // 喂给裁判/案例详情的是"全部检索块"(而非仅被答案 [n] 引用的块):
      // 避免应答 LLM 引用编号飘了导致快照为空、正确答案被误判为"无出处"。
      log.setCitationsSnapshot(toJson(citationLinker.allRetrieved(retrievedChunks)));
      log.setRetrievalStrategy(retrievalStrategy);
      log.setAnswerMode(answerMode);
      log.setLlmModel(llmModel);
      log.setPromptTokens(response.getTokens() == null ? 0 : response.getTokens().prompt());
      log.setCompletionTokens(response.getTokens() == null ? 0 : response.getTokens().completion());
      log.setRetrievalLatencyMs(response.getLatency() == null ? 0 : (int) response.getLatency().retrieval());
      log.setLlmLatencyMs(response.getLatency() == null ? 0 : (int) response.getLatency().llm());
      log.setTotalLatencyMs((int) (System.currentTimeMillis() - start));
      log.setTraceId(TraceIds.current());
      log.setGuardRailResult(response.getGuardRailResult());
      log.setCreatedAt(LocalDateTime.now());
      answerLogMapper.insertAnswerLog(log);
      publishJudgeAsync(request, kbIds, log.getId());
    } catch (Exception e) {
      log.warn("answer log write failed: {}", e.getMessage());
    }
  }

  private void publishJudgeAsync(AnswerRequest request, List<Long> kbIds, Long answerLogId) {
    try {
      SampleRequest req =
          new SampleRequest(
              answerLogId,
              kbIds.toArray(new Long[0]),
              tenantIdOrDefault(),
              request.getJudgeSource() != null ? request.getJudgeSource() : "PRODUCTION",
              Boolean.TRUE.equals(request.getForceSample()));
      AnswerJudgeMessage msg = new AnswerJudgeMessage();
      msg.setAnswerLogId(answerLogId);
      msg.setSource(req.source());
      msg.setGoldenQuestionId(request.getGoldenQuestionId());
      msg.setForceSample(req.forceSample() ? "FORCE" : "AUTO");
      msg.setRequestedAt(LocalDateTime.now());
      answerJudgeProducer.publishJudgeRequest(msg, req);
    } catch (Exception e) {
      log.warn("Judge async publish failed (non-fatal): {}", e.getMessage());
    }
  }

  private String tenantIdOrDefault() {
    return "default"; // 租户模型已移除，judge 采样维度统一 default（待迁移删列）
  }

  private String toJson(Object value) throws JsonProcessingException {
    return objectMapper.writeValueAsString(value == null ? List.of() : value);
  }

  private void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
    } catch (IOException e) {
      throw new BizException(500, "SSE_SEND_FAILED");
    }
  }

  private void sendError(SseEmitter emitter, String error, String message) {
    try {
      emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(Map.of("error", error, "message", message))));
      emitter.complete();
    } catch (Exception e) {
      emitter.completeWithError(e);
    }
  }

  private void validateRequest(AnswerRequest request) {
    if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
      throw new BizException(400, "QUERY_REQUIRED");
    }
    if (request.getKbIds() == null || request.getKbIds().isEmpty()) {
      throw new BizException(400, "KB_IDS_REQUIRED");
    }
  }

  private SearchResponse toSearchResponse(RetrievalOutput output) {
    return new SearchResponse(
        output.getResults(),
        output.getLatencyMs(),
        output.getStrategy(),
        output.getRewrittenQueries(),
        output.getRewriteLatencyMs(),
        output.getVectorLatencyMs(),
        output.getKeywordLatencyMs(),
        output.getRerankLatencyMs());
  }

  List<SearchResult> safeResultsForStream(RetrievalOutput output) {
    if (output.getResults() == null || output.getResults().isEmpty()) {
      return List.of();
    }
    return output.getResults().stream().map(this::maskResultContent).toList();
  }

  private SearchResult maskResultContent(SearchResult source) {
    SearchResult masked = new SearchResult();
    masked.setChunkId(source.getChunkId());
    masked.setDocId(source.getDocId());
    masked.setFilename(source.getFilename());
    masked.setContent(piiMaskCleaner.mask(source.getContent()));
    masked.setChunkIndex(source.getChunkIndex());
    masked.setVectorScore(source.getVectorScore());
    masked.setBm25Score(source.getBm25Score());
    masked.setFinalScore(source.getFinalScore());
    masked.setChunkType(source.getChunkType());
    masked.setChunkModality(source.getChunkModality());
    masked.setImageKey(source.getImageKey());
    return masked;
  }

  private static List<Long> distinct(List<Long> ids) {
    return new ArrayList<>(new LinkedHashSet<>(ids));
  }

  private static String normalizeMode(String mode) {
    return textOrDefault(mode, "OFF").trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isGoldenSetReplay(AnswerRequest request) {
    return "GOLDEN_SET".equalsIgnoreCase(request.getJudgeSource());
  }

  private static String guardMessage(GuardRailResult result) {
    return switch (result) {
      case NO_CITATIONS -> "应答缺少引用";
      case PII_LEAK -> "应答包含疑似 PII";
      case OUT_OF_SCOPE -> "应答超出知识库范围";
      case PASS -> "PASS";
    };
  }

  private static int clamp(int value, int min, int max) {
    return Math.min(Math.max(value <= 0 ? min : value, min), max);
  }

  private static String textOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private interface TokenSink {
    void accept(String delta);
  }
}

@Component
class PromptBuilder {
  String build(String query, List<SearchResult> chunks, KnowledgeBase kb) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是 RAGForge 助手。基于以下参考资料回答问题，必须用 [n] 标号引用。\n");
    sb.append("不要使用参考资料之外的信息；如果资料不足，说明不足并引用最相关资料。\n\n");
    for (int i = 0; i < chunks.size(); i++) {
      SearchResult c = chunks.get(i);
      sb.append("[").append(i + 1).append("] ");
      if ("IMAGE".equalsIgnoreCase(c.getChunkModality())) {
        sb.append("（以下内容来自图片 OCR + 上下文）\n");
      }
      sb.append(c.getContent() == null ? "" : c.getContent()).append("\n\n");
    }
    sb.append("用户问题：").append(query).append("\n");
    sb.append("回答时必须在每个事实后面带 [n] 引用标号。");
    return sb.toString();
  }
}

@Slf4j
@Component
@RequiredArgsConstructor
class CitationLinker {
  private static final Pattern REF = Pattern.compile("\\[(\\d+)]");

  private final ChunkImageResolver chunkImageResolver;

  List<Citation> link(String answer, List<SearchResult> chunks) {
    Set<Integer> citedIndices = extractIndices(answer);
    if (citedIndices.isEmpty()) {
      return List.of();
    }
    // 检索链路不回填 image_key，故被引用的 IMAGE chunk 统一走 ChunkImageResolver 批量解析：
    // 一条 JOIN 取 image_key+bucket 再批量预签名（列缺失时降级为空 map）。
    Map<Long, String> imageUrlByChunk =
        chunkImageResolver.presignedUrls(citedImageChunkIds(citedIndices, chunks));
    List<Citation> citations = new ArrayList<>();
    for (int idx : citedIndices) {
      if (idx < 1 || chunks == null || idx > chunks.size()) {
        continue;
      }
      SearchResult c = chunks.get(idx - 1);
      Citation citation = new Citation();
      citation.setId(idx);
      citation.setChunkId(c.getChunkId());
      citation.setDocId(c.getDocId());
      citation.setModality(textOrDefault(c.getChunkModality(), "TEXT"));
      citation.setTextSnippet(truncate(c.getContent(), 300));
      if ("IMAGE".equalsIgnoreCase(c.getChunkModality())) {
        citation.setImageUrl(imageUrlByChunk.get(c.getChunkId()));
      }
      citations.add(citation);
    }
    return citations;
  }

  /**
   * 全量检索快照:不按答案的 [n] 引用过滤,返回所有检索到的块(按检索顺序编号)。 用于 LLM-as-Judge 评测与案例详情——即使应答 LLM 把引用编号写飘了(如只检索到 1 个块却引用
   * [5]/[6]/[7]),裁判仍能拿到真实检索上下文,不会把内容正确的答案误判成"无出处/完全编造"。
   */
  List<Citation> allRetrieved(List<SearchResult> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return List.of();
    }
    List<Citation> citations = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      SearchResult c = chunks.get(i);
      Citation citation = new Citation();
      citation.setId(i + 1);
      citation.setChunkId(c.getChunkId());
      citation.setDocId(c.getDocId());
      citation.setModality(textOrDefault(c.getChunkModality(), "TEXT"));
      citation.setTextSnippet(truncate(c.getContent(), 300));
      citations.add(citation);
    }
    return citations;
  }

  /** 收集被引用的 IMAGE chunk 的 chunkId，用于批量取 image_key。 */
  private static List<Long> citedImageChunkIds(Set<Integer> citedIndices, List<SearchResult> chunks) {
    if (chunks == null) {
      return List.of();
    }
    List<Long> ids = new ArrayList<>();
    for (int idx : citedIndices) {
      if (idx < 1 || idx > chunks.size()) {
        continue;
      }
      SearchResult c = chunks.get(idx - 1);
      if ("IMAGE".equalsIgnoreCase(c.getChunkModality()) && c.getChunkId() != null) {
        ids.add(c.getChunkId());
      }
    }
    return ids;
  }

  Set<Integer> extractIndices(String answer) {
    Set<Integer> indices = new LinkedHashSet<>();
    if (answer == null || answer.isBlank()) {
      return indices;
    }
    java.util.regex.Matcher matcher = REF.matcher(answer);
    while (matcher.find()) {
      indices.add(Integer.parseInt(matcher.group(1)));
    }
    return indices;
  }

  /**
   * 规范化答案中的 [n] 引用编号:越界(&lt;1 或 &gt;检索块数)的编号收敛到 [1](排名最高的检索块), 有效编号保持不变。避免应答 LLM 幻觉出的悬空编号(如只 1 个块却写 [5])在页面上指向不存在的块。
   */
  static String normalizeCitationMarkers(String answer, int chunkCount) {
    if (answer == null || answer.isBlank() || chunkCount <= 0 || !answer.contains("[")) {
      return answer;
    }
    java.util.regex.Matcher matcher = REF.matcher(answer);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      int k = Integer.parseInt(matcher.group(1));
      int fixed = (k >= 1 && k <= chunkCount) ? k : 1;
      matcher.appendReplacement(sb, "[" + fixed + "]");
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String textOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}

@Component
class GuardRails {
  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
  private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

  GuardRailResult check(String answer, List<Citation> citations) {
    if (citations == null || citations.isEmpty()) {
      return GuardRailResult.NO_CITATIONS;
    }
    if (containsPii(answer)) {
      return GuardRailResult.PII_LEAK;
    }
    return GuardRailResult.PASS;
  }

  private boolean containsPii(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    return EMAIL.matcher(answer).find() || PHONE.matcher(answer).find() || ID_CARD.matcher(answer).find();
  }
}
