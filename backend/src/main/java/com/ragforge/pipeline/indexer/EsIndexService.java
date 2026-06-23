package com.ragforge.pipeline.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsIndexService {

  public static final String INDEX_NAME = "ragforge_chunks";

  private static final int BULK_BATCH_SIZE = 50;

  private final ElasticsearchClient client;

  @PostConstruct
  public void init() {
    try {
      createIndexIfNotExists();
    } catch (Exception e) {
      // ES is a secondary store; failures should not block app startup.
      log.warn("Failed to init ES index: {}", e.getMessage());
    }
  }

  public void createIndexIfNotExists() {
    try {
      ExistsRequest existsRequest = new ExistsRequest.Builder().index(INDEX_NAME).build();
      boolean exists = client.indices().exists(existsRequest).value();
      if (exists) {
        return;
      }

      try {
        client.indices().create(buildCreateIndexRequest(true));
        log.info("Created Elasticsearch index: {} (ik analyzers)", INDEX_NAME);
      } catch (Exception ikError) {
        if (!isIkAnalyzerMissing(ikError)) {
          throw ikError;
        }
        log.warn(
            "IK analyzer not available on ES cluster, falling back to standard: {}",
            ikError.getMessage());
        client.indices().create(buildCreateIndexRequest(false));
        log.info("Created Elasticsearch index: {} (standard analyzer)", INDEX_NAME);
      }
    } catch (Exception e) {
      // ES may be down; don't block app startup.
      log.warn("Failed to create index {}: {}", INDEX_NAME, e.getMessage());
    }
  }

  private CreateIndexRequest buildCreateIndexRequest(boolean useIkAnalyzer) {
    return new CreateIndexRequest.Builder()
        .index(INDEX_NAME)
        .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
        .mappings(
            m ->
                m.properties("chunk_id", Property.of(p -> p.long_(l -> l)))
                    .properties("doc_id", Property.of(p -> p.long_(l -> l)))
                    .properties("kb_id", Property.of(p -> p.long_(l -> l)))
                    .properties("filename", Property.of(p -> p.keyword(k -> k)))
                    .properties("parse_status", Property.of(p -> p.keyword(k -> k)))
                    .properties(
                        "content",
                        Property.of(
                            p ->
                                useIkAnalyzer
                                    ? p.text(
                                        t ->
                                            t.analyzer("ik_max_word").searchAnalyzer("ik_smart"))
                                    : p.text(t -> t.analyzer("standard"))))
                    .properties("chunk_index", Property.of(p -> p.integer(i -> i)))
                    .properties("created_at", Property.of(p -> p.date(d -> d))))
        .build();
  }

  private static boolean isIkAnalyzerMissing(Exception e) {
    String msg = e.getMessage();
    return msg != null && (msg.contains("ik_max_word") || msg.contains("ik_smart"));
  }

  public boolean indexChunks(List<DocumentChunk> chunks, Document doc) {
    if (chunks == null || chunks.isEmpty() || doc == null) {
      return true;
    }

    try {
      boolean success = true;
      for (int i = 0; i < chunks.size(); i += BULK_BATCH_SIZE) {
        List<DocumentChunk> batch = chunks.subList(i, Math.min(i + BULK_BATCH_SIZE, chunks.size()));
        success = bulkIndex(batch, doc) && success;
      }
      return success;
    } catch (Exception e) {
      log.warn("Failed to index chunks to ES for docId={}: {}", doc.getId(), e.getMessage());
      return false;
    }
  }

  private boolean bulkIndex(List<DocumentChunk> chunks, Document doc) {
    long start = System.currentTimeMillis();

    List<BulkOperation> ops = new ArrayList<>(chunks.size());
    for (DocumentChunk c : chunks) {
      if (c.getId() == null) {
        continue;
      }
      Map<String, Object> source = toSource(c, doc);
      ops.add(
          BulkOperation.of(
              o ->
                  o.index(
                      idx ->
                          idx.index(INDEX_NAME)
                              .id(String.valueOf(c.getId()))
                              .document(source))));
    }

    if (ops.isEmpty()) {
      return true;
    }

    try {
      BulkRequest request = new BulkRequest.Builder().operations(ops).build();
      BulkResponse response = client.bulk(request);

      long elapsed = System.currentTimeMillis() - start;
      if (response.errors()) {
        log.warn(
            "ES bulk index had errors (docId={}, batchSize={}, elapsedMs={})",
            doc.getId(),
            ops.size(),
            elapsed);
        return false;
      } else {
        log.info(
            "ES bulk indexed chunks (docId={}, batchSize={}, elapsedMs={})",
            doc.getId(),
            ops.size(),
            elapsed);
        return true;
      }
    } catch (Exception e) {
      log.warn(
          "ES bulk index failed (docId={}, batchSize={}): {}",
          doc.getId(),
          ops.size(),
          e.getMessage());
      return false;
    }
  }

  public long countByDocId(Long docId) {
    if (docId == null) {
      return 0;
    }
    try {
      CountResponse response =
          client.count(
              new CountRequest.Builder()
                  .index(INDEX_NAME)
                  .query(q -> q.term(t -> t.field("doc_id").value(v -> v.longValue(docId))))
                  .build());
      return response.count();
    } catch (Exception e) {
      log.warn("ES countByDocId failed: docId={}, err={}", docId, e.getMessage());
      return -1;
    }
  }

  public void deleteByDocId(Long docId) {
    if (docId == null) {
      return;
    }
    try {
      client.deleteByQuery(
          r ->
              r.index(INDEX_NAME)
                  .query(q -> q.term(t -> t.field("doc_id").value(v -> v.longValue(docId)))));
      client.indices().refresh(r -> r.index(INDEX_NAME));
      log.info("ES deleteByDocId completed: docId={}", docId);
    } catch (Exception e) {
      log.warn("ES deleteByDocId failed: docId={}, err={}", docId, e.getMessage());
    }
  }

  public void deleteByKbId(Long kbId) {
    if (kbId == null) {
      return;
    }
    try {
      client.deleteByQuery(
          r ->
              r.index(INDEX_NAME)
                  .query(q -> q.term(t -> t.field("kb_id").value(v -> v.longValue(kbId)))));
      client.indices().refresh(r -> r.index(INDEX_NAME));
      log.info("ES deleteByKbId completed: kbId={}", kbId);
    } catch (Exception e) {
      log.warn("ES deleteByKbId failed: kbId={}, err={}", kbId, e.getMessage());
    }
  }

  private Map<String, Object> toSource(DocumentChunk chunk, Document doc) {
    Map<String, Object> m = new HashMap<>();
    m.put("chunk_id", chunk.getId());
    m.put("doc_id", chunk.getDocId());
    m.put("kb_id", chunk.getKbId());
    m.put("filename", doc.getFilename());
    m.put("parse_status", doc.getParseStatus());
    m.put("content", chunk.getContent());
    m.put("chunk_index", chunk.getChunkIndex());

    if (chunk.getCreatedAt() != null) {
      // Store as ISO string; ES date mapping can parse this.
      m.put(
          "created_at",
          chunk.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString());
    }
    return m;
  }
}
