package com.ragforge.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ragforge.config.ElasticsearchClientProvider;
import com.ragforge.pipeline.indexer.EsIndexService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsSearchService {

  private final ElasticsearchClientProvider clientProvider;

  public List<SearchResult> search(String query, List<Long> kbIds, List<Long> docIds, int topK) {
    return search(query, kbIds, docIds, topK, null);
  }

  public List<SearchResult> search(
      String query,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    requireKbScope(kbIds);
    ElasticsearchClient client = clientProvider.get();
    try {
      SearchResponse<Map> response =
          client.search(
              s ->
                  s.index(EsIndexService.INDEX_NAME)
                      .size(topK)
                      .query(
                          q ->
                              q.bool(
                                  b -> {
                                    b.must(m -> m.match(ma -> ma.field("content").query(query)));
                                    b.filter(f -> f.term(t -> t.field("parse_status").value("COMPLETED")));
                                    if (kbIds != null && !kbIds.isEmpty()) {
                                      List<FieldValue> values =
                                          kbIds.stream().map(FieldValue::of).toList();
                                      b.filter(
                                          f ->
                                              f.terms(
                                                  t ->
                                                      t.field("kb_id")
                                                          .terms(ts -> ts.value(values))));
                                    }
                                    if (docIds != null && !docIds.isEmpty()) {
                                      List<FieldValue> docValues =
                                          docIds.stream().map(FieldValue::of).toList();
                                      b.filter(
                                          f ->
                                              f.terms(
                                                  t ->
                                                      t.field("doc_id")
                                                          .terms(ts -> ts.value(docValues))));
                                    }
                                    if (filter != null
                                        && filter.getChunkType() != null
                                        && !filter.getChunkType().isEmpty()) {
                                      List<FieldValue> chunkTypeValues =
                                          filter.getChunkType().stream().map(FieldValue::of).toList();
                                      b.filter(
                                          f ->
                                              f.terms(
                                                  t ->
                                                      t.field("chunk_type")
                                                          .terms(ts -> ts.value(chunkTypeValues))));
                                    }
                                    return b;
                                  })),
              Map.class);

      List<SearchResult> results = new ArrayList<>();
      for (Hit<Map> hit : response.hits().hits()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> source = hit.source();
        if (source == null) {
          continue;
        }
        SearchResult result = new SearchResult();
        result.setChunkId(toLong(source.get("chunk_id")));
        result.setDocId(toLong(source.get("doc_id")));
        result.setFilename(stringVal(source.get("filename")));
        result.setContent(stringVal(source.get("content")));
        result.setChunkIndex(toInt(source.get("chunk_index")));
        result.setVectorScore(0);
        result.setBm25Score(hit.score() != null ? hit.score() : 0);
        result.setChunkType(source.get("chunk_type") == null ? null : source.get("chunk_type").toString());
        results.add(result);
      }
      return results;
    } catch (Exception e) {
      log.warn("ES keyword search failed: query={}, err={}", query, e.getMessage());
      return List.of();
    }
  }

  private static void requireKbScope(List<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      throw new IllegalStateException("Search requires non-empty kbIds");
    }
  }

  private static long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return 0L;
  }

  private static int toInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    return 0;
  }

  private static String stringVal(Object value) {
    return value != null ? value.toString() : "";
  }
}
