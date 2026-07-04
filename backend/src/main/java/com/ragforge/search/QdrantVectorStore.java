package com.ragforge.search;

import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.matchValues;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

import com.ragforge.common.BizException;
import com.ragforge.config.QdrantProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Qdrant 向量库操作封装：向量的写入(upsert)、删除、ANN 检索。
 *
 * <p>约定：Qdrant point id = document_chunks.id（chunk_id），payload 仅存过滤字段
 * kb_id / doc_id / chunk_type，正文回 PG 取。所有对外错误均净化为友好中文，不泄露 gRPC 细节。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantVectorStore {

  private final QdrantClient qdrantClient;
  private final QdrantProperties props;

  /** 一个待写入的 chunk 向量点。 */
  public record ChunkPoint(long chunkId, long kbId, long docId, String chunkType, float[] vector) {}

  /** 一条命中结果：chunk_id + 相似度分。 */
  public record ScoredChunk(long chunkId, double score) {}

  /** 批量写入/更新向量点（幂等 upsert）。 */
  public void upsert(List<ChunkPoint> points) {
    if (points == null || points.isEmpty()) {
      return;
    }
    List<PointStruct> structs = new ArrayList<>(points.size());
    for (ChunkPoint p : points) {
      if (p.vector() == null || p.vector().length != props.getVectorDim()) {
        throw new BizException(
            "向量维度不符合要求，写入向量库失败"); // 内部细节(实际维度)记日志，不外泄
      }
      structs.add(
          PointStruct.newBuilder()
              .setId(id(p.chunkId()))
              .setVectors(vectors(p.vector()))
              .putPayload("kb_id", value(p.kbId()))
              .putPayload("doc_id", value(p.docId()))
              .putPayload("chunk_type", value(p.chunkType() == null ? "" : p.chunkType()))
              .build());
    }
    try {
      qdrantClient
          .upsertAsync(props.getCollection(), structs)
          .get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("向量写入被中断，请稍后重试");
    } catch (Exception e) {
      log.error("Qdrant upsert 失败 collection={} count={}", props.getCollection(), structs.size(), e);
      throw new BizException("向量库写入暂时异常，请稍后重试");
    }
  }

  /**
   * 按文档删除其全部向量点（用于删文档 / rechunk / reprocess 前清理）。 与 ES 删除一致采用 best-effort：失败仅告警不阻断业务，残留由对账兜底。
   */
  public void deleteByDocId(long docId) {
    Filter filter = Filter.newBuilder().addMust(matchValues("doc_id", List.of(docId))).build();
    try {
      qdrantClient
          .deleteAsync(props.getCollection(), filter)
          .get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Qdrant deleteByDocId 被中断 docId={}", docId);
    } catch (Exception e) {
      log.warn("Qdrant deleteByDocId 失败(将由对账兜底) docId={}: {}", docId, e.getMessage());
    }
  }

  /** 按 chunk id 批量删除向量点（best-effort）。 */
  public void deleteByChunkIds(List<Long> chunkIds) {
    if (chunkIds == null || chunkIds.isEmpty()) {
      return;
    }
    List<PointId> ids = new ArrayList<>(chunkIds.size());
    for (Long cid : chunkIds) {
      ids.add(id(cid));
    }
    try {
      qdrantClient
          .deleteAsync(props.getCollection(), ids)
          .get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Qdrant deleteByChunkIds 被中断 count={}", chunkIds.size());
    } catch (Exception e) {
      log.warn("Qdrant deleteByChunkIds 失败(将由对账兜底) count={}: {}", chunkIds.size(), e.getMessage());
    }
  }

  /**
   * ANN 检索：按 kb_id / doc_id / chunk_type 过滤，返回 topK 命中（chunk_id + 分数）。
   *
   * @param queryVector 查询向量（维度须与 collection 一致）
   * @param kbIds 知识库过滤（必填，非空）
   * @param docIds 文档过滤（可空）
   * @param chunkTypes chunk 类型过滤（可空）
   * @param topK 返回数量
   */
  public List<ScoredChunk> search(
      float[] queryVector,
      List<Long> kbIds,
      List<Long> docIds,
      List<String> chunkTypes,
      int topK) {
    if (queryVector == null || queryVector.length != props.getVectorDim()) {
      throw new BizException("查询向量维度异常，检索失败");
    }
    Filter.Builder fb = Filter.newBuilder();
    if (kbIds != null && !kbIds.isEmpty()) {
      fb.addMust(matchValues("kb_id", kbIds));
    }
    if (docIds != null && !docIds.isEmpty()) {
      fb.addMust(matchValues("doc_id", docIds));
    }
    if (chunkTypes != null && !chunkTypes.isEmpty()) {
      fb.addMust(matchKeywords("chunk_type", chunkTypes));
    }
    List<Float> vec = new ArrayList<>(queryVector.length);
    for (float v : queryVector) {
      vec.add(v);
    }
    SearchPoints.Builder sb =
        SearchPoints.newBuilder()
            .setCollectionName(props.getCollection())
            .addAllVector(vec)
            .setLimit(topK)
            // 只要 id+score，不取 payload，减少传输
            .setWithPayload(WithPayloadSelector.newBuilder().setEnable(false).build())
            .setFilter(fb.build());
    try {
      List<ScoredPoint> hits =
          qdrantClient.searchAsync(sb.build()).get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
      List<ScoredChunk> out = new ArrayList<>(hits.size());
      for (ScoredPoint sp : hits) {
        out.add(new ScoredChunk(sp.getId().getNum(), sp.getScore()));
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("向量检索被中断，请稍后重试");
    } catch (Exception e) {
      log.error("Qdrant search 失败 topK={} kbCount={}", topK, kbIds == null ? 0 : kbIds.size(), e);
      throw new BizException("向量检索服务暂时不可用，请稍后重试");
    }
  }
}
