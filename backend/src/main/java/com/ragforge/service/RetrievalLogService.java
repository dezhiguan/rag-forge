package com.ragforge.service;

import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.RetrievalLog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetrievalLogService {

  private final RetrievalLogMapper retrievalLogMapper;

  @Async
  public void logAsync(
      String query,
      String strategy,
      List<Long> kbIds,
      List<String> rewrittenQueries,
      int topK,
      int resultCount,
      long latencyMs) {
    RetrievalLog log = new RetrievalLog();
    log.setQuery(query);
    log.setStrategy(strategy);
    if (kbIds != null && !kbIds.isEmpty()) {
      log.setKbIds(kbIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }
    if (rewrittenQueries != null && !rewrittenQueries.isEmpty()) {
      log.setRewrittenQueries(String.join("\n", rewrittenQueries));
    }
    log.setTopK(topK);
    log.setResultCount(resultCount);
    log.setLatencyMs((int) latencyMs);
    log.setCreatedAt(LocalDateTime.now());
    retrievalLogMapper.insert(log);
  }
}
