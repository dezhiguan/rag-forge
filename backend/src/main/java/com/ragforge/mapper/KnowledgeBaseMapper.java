package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

  /**
   * 原子调整知识库的 chunk/doc 计数。用 {@code col = col + delta} 的原子更新,避免"读-改-写"在多线程/多
   * pod 下丢更新;且只改这两列 + updated_at,杜绝 {@code updateById} 全行回写把陈旧快照覆盖到其它字段。
   * 计数不下溢为负;已删除的知识库不受影响。
   *
   * @return 受影响行数(0 表示 kbId 不存在或库已删除)
   */
  @Update(
      """
      UPDATE knowledge_bases
         SET chunk_count = GREATEST(0, COALESCE(chunk_count, 0) + #{chunkDelta}),
             doc_count   = GREATEST(0, COALESCE(doc_count, 0) + #{docDelta}),
             updated_at  = NOW()
       WHERE id = #{kbId} AND status <> 'deleted'
      """)
  int adjustCounters(
      @Param("kbId") Long kbId, @Param("chunkDelta") int chunkDelta, @Param("docDelta") int docDelta);
}
