package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.ModelUsageDaily;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelUsageDailyMapper extends BaseMapper<ModelUsageDaily> {

  /**
   * 按用途聚合自 {@code since}（含）起的 token 与成本，供驾驶舱成本卡使用。 orgId 为 null 时统计全平台（管理员破玻璃），
   * 否则只统计该组织。
   */
  @Select(
      """
      SELECT lower(purpose) AS purpose,
             COALESCE(SUM(input_tokens + output_tokens), 0) AS tokens,
             COALESCE(SUM(cost), 0) AS cost
      FROM model_usage_daily
      WHERE stat_date >= #{since}
        AND (#{orgId,jdbcType=BIGINT} IS NULL OR org_id = #{orgId,jdbcType=BIGINT})
      GROUP BY lower(purpose)
      """)
  List<Map<String, Object>> aggregateCostSince(
      @Param("since") LocalDate since, @Param("orgId") Long orgId);

  /** 按 (model_code, stat_date) 累加写入；冲突即累加，用于计量批量落库。 */
  @Insert(
      """
      INSERT INTO model_usage_daily
        (model_code, purpose, stat_date, org_id, call_count, input_tokens, output_tokens,
         cost, success_count, fail_count, total_latency_ms, updated_at)
      VALUES
        (#{modelCode}, #{purpose}, #{statDate}, #{orgId}, #{callCount}, #{inputTokens}, #{outputTokens},
         #{cost}, #{successCount}, #{failCount}, #{totalLatencyMs}, NOW())
      ON CONFLICT (model_code, stat_date, org_id) DO UPDATE SET
        call_count       = model_usage_daily.call_count       + EXCLUDED.call_count,
        input_tokens     = model_usage_daily.input_tokens     + EXCLUDED.input_tokens,
        output_tokens    = model_usage_daily.output_tokens    + EXCLUDED.output_tokens,
        cost             = model_usage_daily.cost             + EXCLUDED.cost,
        success_count    = model_usage_daily.success_count    + EXCLUDED.success_count,
        fail_count       = model_usage_daily.fail_count       + EXCLUDED.fail_count,
        total_latency_ms = model_usage_daily.total_latency_ms + EXCLUDED.total_latency_ms,
        updated_at       = NOW()
      """)
  void upsertAccumulate(
      @Param("modelCode") String modelCode,
      @Param("purpose") String purpose,
      @Param("statDate") LocalDate statDate,
      @Param("orgId") long orgId,
      @Param("callCount") long callCount,
      @Param("inputTokens") long inputTokens,
      @Param("outputTokens") long outputTokens,
      @Param("cost") BigDecimal cost,
      @Param("successCount") long successCount,
      @Param("failCount") long failCount,
      @Param("totalLatencyMs") long totalLatencyMs);
}
