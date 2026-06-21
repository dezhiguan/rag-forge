package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.AnswerLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnswerLogMapper extends BaseMapper<AnswerLog> {

  @Insert(
      """
      INSERT INTO answer_logs (
        tenant_id, principal_id, kb_ids, query, answer, citations_snapshot,
        retrieval_strategy, answer_mode, llm_model, prompt_tokens, completion_tokens,
        retrieval_latency_ms, llm_latency_ms, total_latency_ms, trace_id,
        guard_rail_result, created_at
      ) VALUES (
        #{tenantId}, #{principalId}, string_to_array(#{kbIdsCsv}, ',')::bigint[],
        #{query}, #{answer},
        #{citationsSnapshot,typeHandler=com.ragforge.mybatis.handler.JsonbStringTypeHandler},
        #{retrievalStrategy}, #{answerMode}, #{llmModel}, #{promptTokens}, #{completionTokens},
        #{retrievalLatencyMs}, #{llmLatencyMs}, #{totalLatencyMs}, #{traceId},
        #{guardRailResult}, #{createdAt}
      )
      """)
  int insertAnswerLog(AnswerLog log);
}
