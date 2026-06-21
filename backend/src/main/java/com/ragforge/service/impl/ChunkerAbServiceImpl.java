package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.common.TextNormalizer;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.model.dto.ChunkerAbRequest;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.vo.ChunkerAbResponse;
import com.ragforge.pipeline.chunker.Chunk;
import com.ragforge.pipeline.chunker.ChunkParams;
import com.ragforge.pipeline.chunker.ChunkerStrategy;
import com.ragforge.pipeline.chunker.CleanedText;
import com.ragforge.pipeline.chunker.DocumentMeta;
import com.ragforge.service.ChunkerAbService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ChunkerAbServiceImpl implements ChunkerAbService {

  private static final Pattern EXPECTED_ID_PATTERN = Pattern.compile("\\d+");

  private final EvalDatasetMapper evalDatasetMapper;
  private final EvalQuestionMapper evalQuestionMapper;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final List<ChunkerStrategy> strategies;

  @Override
  public ChunkerAbResponse run(ChunkerAbRequest request) {
    if (request == null || request.getEvalDatasetId() == null) {
      throw new BizException(400, "EVAL_DATASET_ID_REQUIRED");
    }
    EvalDataset dataset = evalDatasetMapper.selectById(request.getEvalDatasetId());
    if (dataset == null) {
      throw new BizException(404, "EVAL_DATASET_NOT_FOUND");
    }
    List<EvalQuestion> questions =
        evalQuestionMapper.selectList(
            new LambdaQueryWrapper<EvalQuestion>().eq(EvalQuestion::getDatasetId, dataset.getId()));
    if (questions.isEmpty()) {
      return new ChunkerAbResponse(List.of());
    }

    Map<Long, String> docTexts = loadDocumentTexts(dataset.getKbId());
    Map<Long, String> expectedChunkTexts = loadExpectedChunkTexts(questions);
    Map<String, ChunkerStrategy> strategyByName =
        strategies.stream()
            .collect(
                Collectors.toMap(
                    strategy -> strategy.name().toUpperCase(Locale.ROOT),
                    strategy -> strategy,
                    (left, right) -> left,
                    LinkedHashMap::new));

    ChunkParams params = request.getParams() == null ? new ChunkParams() : request.getParams();
    List<String> requested =
        request.getStrategies() == null || request.getStrategies().isEmpty()
            ? List.of("RECURSIVE", "MARKDOWN_HEADING", "SEMANTIC")
            : request.getStrategies();

    List<ChunkerAbResponse.ResultItem> results = new ArrayList<>();
    for (String strategyName : requested) {
      ChunkerStrategy strategy = strategyByName.get(normalizeStrategy(strategyName));
      if (strategy == null) {
        continue;
      }
      List<ScoredChunk> generated = splitAll(docTexts, strategy, params);
      results.add(evaluate(strategy.name(), generated, questions, expectedChunkTexts));
    }
    return new ChunkerAbResponse(results);
  }

  private Map<Long, String> loadDocumentTexts(Long kbId) {
    return jdbcTemplate.query(
        """
        SELECT doc_id, string_agg(content, E'\\n\\n' ORDER BY chunk_index) AS content
        FROM document_chunks
        WHERE kb_id = ?
        GROUP BY doc_id
        ORDER BY doc_id DESC
        LIMIT 200
        """,
        rs -> {
          Map<Long, String> map = new LinkedHashMap<>();
          while (rs.next()) {
            map.put(rs.getLong("doc_id"), rs.getString("content"));
          }
          return map;
        },
        kbId);
  }

  private Map<Long, String> loadExpectedChunkTexts(List<EvalQuestion> questions) {
    List<Long> ids =
        questions.stream()
            .flatMap(question -> parseExpectedIds(question.getExpectedDocIds()).stream())
            .distinct()
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    return jdbcTemplate.query(
        "SELECT id, content FROM document_chunks WHERE id IN (" + placeholders + ")",
        rs -> {
          Map<Long, String> map = new LinkedHashMap<>();
          while (rs.next()) {
            map.put(rs.getLong("id"), rs.getString("content"));
          }
          return map;
        },
        ids.toArray());
  }

  private List<ScoredChunk> splitAll(
      Map<Long, String> docTexts, ChunkerStrategy strategy, ChunkParams params) {
    List<ScoredChunk> chunks = new ArrayList<>();
    for (Map.Entry<Long, String> entry : docTexts.entrySet()) {
      DocumentMeta meta = new DocumentMeta();
      meta.setDocId(entry.getKey());
      meta.setContentType("MARKDOWN_HEADING".equals(strategy.name()) ? "text/markdown" : "text/plain");
      meta.setFilename("MARKDOWN_HEADING".equals(strategy.name()) ? "chunker-ab.md" : "chunker-ab.txt");
      if (!strategy.supports(meta)) {
        continue;
      }
      List<Chunk> split = strategy.split(new CleanedText(entry.getValue()), params);
      for (Chunk chunk : split) {
        chunks.add(new ScoredChunk(entry.getKey(), chunk.getSeq(), chunk.getContent()));
      }
    }
    return chunks;
  }

  private ChunkerAbResponse.ResultItem evaluate(
      String strategy,
      List<ScoredChunk> chunks,
      List<EvalQuestion> questions,
      Map<Long, String> expectedChunkTexts) {
    int totalChunks = chunks.size();
    int avgChunkLen =
        totalChunks == 0
            ? 0
            : (int) Math.round(chunks.stream().mapToInt(chunk -> chunk.content().length()).average().orElse(0));

    int top1Hits = 0;
    double mrr = 0.0;
    for (EvalQuestion question : questions) {
      List<String> expectedSnippets = expectedSnippets(question, expectedChunkTexts);
      if (expectedSnippets.isEmpty()) {
        continue;
      }
      List<ScoredChunk> ranked = rank(chunks, question.getQuestion());
      Integer hitRank = firstHitRank(ranked, expectedSnippets);
      if (hitRank != null) {
        if (hitRank == 1) {
          top1Hits++;
        }
        mrr += 1.0 / hitRank;
      }
    }
    int questionCount = Math.max(1, questions.size());
    return new ChunkerAbResponse.ResultItem(
        strategy,
        round(top1Hits * 1.0 / questionCount),
        round(mrr / questionCount),
        avgChunkLen,
        totalChunks);
  }

  private List<ScoredChunk> rank(List<ScoredChunk> chunks, String question) {
    List<String> terms =
        TextNormalizer.normalize(question).chars().mapToObj(ch -> String.valueOf((char) ch)).distinct().toList();
    return chunks.stream()
        .sorted(
            Comparator.comparingInt((ScoredChunk chunk) -> score(chunk.content(), terms)).reversed()
                .thenComparingLong(ScoredChunk::docId)
                .thenComparingInt(ScoredChunk::seq))
        .toList();
  }

  private static int score(String content, List<String> terms) {
    String normalized = TextNormalizer.normalize(content);
    int score = 0;
    for (String term : terms) {
      if (normalized.contains(term)) {
        score++;
      }
    }
    return score;
  }

  private Integer firstHitRank(List<ScoredChunk> ranked, List<String> snippets) {
    for (int i = 0; i < ranked.size(); i++) {
      for (String snippet : snippets) {
        if (TextNormalizer.normalizedContains(ranked.get(i).content(), snippet)) {
          return i + 1;
        }
      }
    }
    return null;
  }

  private List<String> expectedSnippets(
      EvalQuestion question, Map<Long, String> expectedChunkTexts) {
    List<String> snippets = parseExpectedTextSnippets(question.getExpectedTextSnippets());
    if (!snippets.isEmpty()) {
      return snippets;
    }
    return parseExpectedIds(question.getExpectedDocIds()).stream()
        .map(expectedChunkTexts::get)
        .filter(StringUtils::hasText)
        .map(text -> TextNormalizer.snippet(text, 120))
        .toList();
  }

  private List<String> parseExpectedTextSnippets(String value) {
    if (!StringUtils.hasText(value)) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, new TypeReference<List<String>>() {});
    } catch (Exception ignored) {
      return List.of(value);
    }
  }

  private static List<Long> parseExpectedIds(String value) {
    if (!StringUtils.hasText(value)) {
      return List.of();
    }
    List<Long> ids = new ArrayList<>();
    var matcher = EXPECTED_ID_PATTERN.matcher(value);
    while (matcher.find()) {
      ids.add(Long.parseLong(matcher.group()));
    }
    return ids;
  }

  private static String normalizeStrategy(String strategy) {
    return strategy == null ? "" : strategy.trim().toUpperCase(Locale.ROOT);
  }

  private static double round(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }

  private record ScoredChunk(Long docId, int seq, String content) {}
}
