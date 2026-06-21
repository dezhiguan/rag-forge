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
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.pipeline.cleaner.L3PiiMaskCleaner;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.LlmService;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.web.TraceIds;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  public SseEmitter answer(AnswerRequest request) {
    validateRequest(request);
    RagAuthContext authContext = RagAuthContextHolder.get();
    SseEmitter emitter = new SseEmitter(120_000L);
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
        });
    return emitter;
  }

  public void validateAnswerMode(AnswerRequest request) {
    validateRequest(request);
    enforceAnswerMode(loadKnowledgeBases(distinct(request.getKbIds())), request.getAnswerMode());
  }

  public AnswerResponse answerBlocking(AnswerRequest request) {
    validateRequest(request);
    return answerInternal(request, null, null).response();
  }

  private AnswerRun answerInternal(AnswerRequest request, TokenSink tokenSink, SseEmitter emitter) {
    long start = System.currentTimeMillis();
    List<Long> kbIds = distinct(request.getKbIds());
    List<KnowledgeBase> kbs = loadKnowledgeBases(kbIds);
    String effectiveMode = enforceAnswerMode(kbs, request.getAnswerMode());
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
      writeLog(request, kbIds, response, effectiveMode, llmModel, retrieval.getStrategy(), start);
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
    List<Citation> citations = citationLinker.link(llmResult.content(), retrieval.getResults());
    GuardRailResult guard = guardRails.check(llmResult.content(), citations);

    AnswerResponse response = new AnswerResponse();
    response.setAnswer(llmResult.content());
    response.setCitations(citations);
    response.setRetrieval(retrievalResponse);
    response.setTokens(new TokenUsage(llmResult.promptTokens(), llmResult.completionTokens()));
    response.setLatency(new Latency(retrieval.getLatencyMs(), llmLatency, System.currentTimeMillis() - start));
    response.setGuardRailResult(guard.name());
    response.setLlmModel(llmModel);
    writeLog(request, kbIds, response, effectiveMode, llmModel, retrieval.getStrategy(), start);

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
        .orElse("qwen-plus");
  }

  private void writeLog(
      AnswerRequest request,
      List<Long> kbIds,
      AnswerResponse response,
      String answerMode,
      String llmModel,
      String retrievalStrategy,
      long start) {
    try {
      RagAuthContext auth = RagAuthContextHolder.get();
      AnswerLog log = new AnswerLog();
      log.setTenantId(auth == null || auth.tenantId() == null ? "default" : auth.tenantId());
      log.setPrincipalId(auth == null ? null : auth.principalId());
      log.setKbIdsCsv(String.join(",", kbIds.stream().map(String::valueOf).toList()));
      log.setQuery(request.getQuery());
      log.setAnswer(response.getAnswer());
      log.setCitationsSnapshot(toJson(response.getCitations()));
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
    } catch (Exception e) {
      log.warn("answer log write failed: {}", e.getMessage());
    }
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

@Component
@RequiredArgsConstructor
class CitationLinker {
  private static final Pattern REF = Pattern.compile("\\[(\\d+)]");
  private static final Duration IMAGE_URL_TTL = Duration.ofMinutes(5);

  private final DocumentMapper documentMapper;
  private final ObjectStorage objectStorage;

  List<Citation> link(String answer, List<SearchResult> chunks) {
    Set<Integer> citedIndices = extractIndices(answer);
    if (citedIndices.isEmpty()) {
      return List.of();
    }
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
        citation.setImageUrl(imageUrl(c));
      }
      citations.add(citation);
    }
    return citations;
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

  private String imageUrl(SearchResult result) {
    Document doc = result.getDocId() == null ? null : documentMapper.selectById(result.getDocId());
    if (doc == null) {
      return null;
    }
    String key = textOrDefault(result.getImageKey(), doc.getStorageKey());
    if (key == null || key.isBlank() || doc.getStorageBucket() == null || doc.getStorageBucket().isBlank()) {
      return null;
    }
    return objectStorage.presignedGet(doc.getStorageBucket(), key, IMAGE_URL_TTL);
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
