package com.ragforge.modelcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.ModelConfigMapper;
import com.ragforge.mapper.ModelUsageDailyMapper;
import com.ragforge.model.entity.ModelConfig;
import com.ragforge.model.entity.ModelUsageDaily;
import com.ragforge.modelcenter.vo.CostDetailVo;
import com.ragforge.modelcenter.vo.CostStatsVo;
import com.ragforge.modelcenter.vo.ModelItemVo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 「模型 & 成本中心」只读聚合服务：列表、看板、明细。 */
@Service
@RequiredArgsConstructor
public class ModelCenterService {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

  private final ModelConfigMapper modelConfigMapper;
  private final ModelUsageDailyMapper usageMapper;
  private final ModelResolver modelResolver;

  /**
   * 启用/停用模型。停用会触发守卫：若停用后该用途无任何可用模型，拒绝并抛 409，避免改坏检索/应答链路。
   *
   * @return 更新后的启用状态
   */
  public boolean toggle(String code, boolean enabled) {
    // 模型启停是全平台动作：仅超管在「全平台视图」(破玻璃)下可操作，组织上下文内一律拒绝。
    if (!com.ragforge.security.AdminOverrideHolder.isActive()) {
      throw new BizException(403, "MODEL_TOGGLE_REQUIRES_PLATFORM_VIEW");
    }
    ModelConfig cfg =
        modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getCode, code));
    if (cfg == null) {
      throw new BizException(404, "MODEL_NOT_FOUND:" + code);
    }
    if (Boolean.valueOf(enabled).equals(cfg.getEnabled())) {
      return enabled; // 幂等，无变更
    }
    if (!enabled) {
      Purpose purpose = parsePurpose(cfg.getPurpose());
      if (purpose != null && !modelResolver.purposeStillResolvableWithout(purpose, code)) {
        throw new BizException(409, "MODEL_DISABLE_WOULD_LEAVE_PURPOSE_UNAVAILABLE:" + purpose.name());
      }
    }
    cfg.setEnabled(enabled);
    cfg.setUpdatedAt(LocalDateTime.now());
    modelConfigMapper.updateById(cfg);
    modelResolver.refresh();
    return enabled;
  }

  private static Purpose parsePurpose(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return Purpose.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 模型列表：每个模型带本月费用。 */
  public List<ModelItemVo> listModels() {
    List<ModelConfig> configs = allConfigsSorted();
    Map<String, BigDecimal> monthlyCostByModel = sumCostByModel(loadMonthUsage());

    List<ModelItemVo> result = new ArrayList<>(configs.size());
    for (ModelConfig c : configs) {
      result.add(
          new ModelItemVo(
              c.getCode(),
              c.getDisplayName(),
              c.getVendor(),
              c.getPurpose(),
              c.getInputPrice(),
              c.getOutputPrice(),
              c.getIsLocal(),
              c.getEnabled(),
              c.getIsPrimary(),
              monthlyCostByModel.getOrDefault(c.getCode(), BigDecimal.ZERO)));
    }
    return result;
  }

  /** 成本看板：KPI + 近 N 天趋势 + 本月费用排行。 */
  public CostStatsVo costStats(int days) {
    int span = days <= 0 ? 7 : Math.min(days, 90);
    List<ModelConfig> configs = allConfigsSorted();
    List<ModelUsageDaily> monthUsage = loadMonthUsage();

    // ---- KPI ----
    int modelCount = configs.size();
    int enabledCount = (int) configs.stream().filter(c -> Boolean.TRUE.equals(c.getEnabled())).count();
    BigDecimal monthlyCost = BigDecimal.ZERO;
    long inTok = 0;
    long outTok = 0;
    long calls = 0;
    long success = 0;
    for (ModelUsageDaily u : monthUsage) {
      monthlyCost = monthlyCost.add(nz(u.getCost()));
      inTok += nzl(u.getInputTokens());
      outTok += nzl(u.getOutputTokens());
      calls += nzl(u.getCallCount());
      success += nzl(u.getSuccessCount());
    }
    double successRate = calls > 0 ? round4((double) success / calls) : 1.0;
    CostStatsVo.Kpi kpi =
        new CostStatsVo.Kpi(
            modelCount, enabledCount, scale2(monthlyCost), inTok, outTok, calls, successRate);

    // ---- 趋势（近 span 天，按用途堆叠费用）----
    LocalDate today = LocalDate.now();
    LocalDate trendStart = today.minusDays(span - 1L);
    List<ModelUsageDaily> trendUsage = loadUsageBetween(trendStart, today);
    Map<LocalDate, Map<String, BigDecimal>> byDate = new LinkedHashMap<>();
    for (LocalDate d = trendStart; !d.isAfter(today); d = d.plusDays(1)) {
      byDate.put(d, new LinkedHashMap<>());
    }
    for (ModelUsageDaily u : trendUsage) {
      Map<String, BigDecimal> bucket = byDate.get(u.getStatDate());
      if (bucket == null) {
        continue;
      }
      bucket.merge(u.getPurpose(), nz(u.getCost()), BigDecimal::add);
    }
    List<CostStatsVo.TrendPoint> trend = new ArrayList<>();
    byDate.forEach(
        (d, bucket) -> {
          Map<String, BigDecimal> scaled = new LinkedHashMap<>();
          bucket.forEach((p, v) -> scaled.put(p, scale2(v)));
          trend.add(new CostStatsVo.TrendPoint(d.format(DATE_FMT), scaled));
        });

    // ---- 排行（本月按模型费用降序）----
    Map<String, BigDecimal> costByModel = sumCostByModel(monthUsage);
    BigDecimal totalMonthly = monthlyCost.signum() == 0 ? BigDecimal.ONE : monthlyCost;
    List<CostStatsVo.RankItem> ranking = new ArrayList<>();
    costByModel.entrySet().stream()
        .filter(e -> e.getValue().signum() > 0)
        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
        .forEach(
            e -> {
              double pct =
                  round2(e.getValue().multiply(BigDecimal.valueOf(100)).doubleValue() / totalMonthly.doubleValue());
              ranking.add(new CostStatsVo.RankItem(e.getKey(), scale2(e.getValue()), pct));
            });

    return new CostStatsVo(kpi, trend, ranking);
  }

  /** 调用明细：本月按用途聚合。 */
  public List<CostDetailVo> costDetail() {
    List<ModelUsageDaily> monthUsage = loadMonthUsage();
    // key: purpose|modelCode
    Map<String, long[]> agg = new LinkedHashMap<>(); // [calls, in, out, latency]
    Map<String, BigDecimal> costAgg = new LinkedHashMap<>();
    Map<String, String> modelOfKey = new LinkedHashMap<>();
    for (ModelUsageDaily u : monthUsage) {
      String key = u.getPurpose() + "|" + u.getModelCode();
      long[] a = agg.computeIfAbsent(key, k -> new long[4]);
      a[0] += nzl(u.getCallCount());
      a[1] += nzl(u.getInputTokens());
      a[2] += nzl(u.getOutputTokens());
      a[3] += nzl(u.getTotalLatencyMs());
      costAgg.merge(key, nz(u.getCost()), BigDecimal::add);
      modelOfKey.put(key, u.getModelCode());
    }
    List<CostDetailVo> result = new ArrayList<>();
    for (Map.Entry<String, long[]> e : agg.entrySet()) {
      String key = e.getKey();
      long[] a = e.getValue();
      String purpose = key.substring(0, key.indexOf('|'));
      long avgLatency = a[0] > 0 ? a[3] / a[0] : 0;
      result.add(
          new CostDetailVo(
              purpose, modelOfKey.get(key), a[0], a[1], a[2], scale2(costAgg.get(key)), avgLatency));
    }
    result.sort(Comparator.comparing(CostDetailVo::cost).reversed());
    return result;
  }

  // ---------- helpers ----------

  private List<ModelConfig> allConfigsSorted() {
    return modelConfigMapper.selectList(
        new LambdaQueryWrapper<ModelConfig>().orderByAsc(ModelConfig::getSortOrder));
  }

  private List<ModelUsageDaily> loadMonthUsage() {
    LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
    return loadUsageBetween(monthStart, LocalDate.now());
  }

  private List<ModelUsageDaily> loadUsageBetween(LocalDate from, LocalDate to) {
    LambdaQueryWrapper<ModelUsageDaily> w =
        new LambdaQueryWrapper<ModelUsageDaily>()
            .ge(ModelUsageDaily::getStatDate, from)
            .le(ModelUsageDaily::getStatDate, to);
    applyOrgScope(w);
    return usageMapper.selectList(w);
  }

  /** 成本按当前组织：破玻璃(全平台视图)不过滤；否则限当前组织(无组织上下文→空，不泄漏全平台)。 */
  private void applyOrgScope(LambdaQueryWrapper<ModelUsageDaily> w) {
    com.ragforge.security.RagAuthContext ctx = com.ragforge.security.RagAuthContextHolder.get();
    if (ctx != null && ctx.isAdmin() && com.ragforge.security.AdminOverrideHolder.isActive()) {
      return;
    }
    Long orgId = com.ragforge.security.OrgContextHolder.get();
    w.eq(ModelUsageDaily::getOrgId, orgId == null ? -1L : orgId);
  }

  private Map<String, BigDecimal> sumCostByModel(List<ModelUsageDaily> usage) {
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    for (ModelUsageDaily u : usage) {
      m.merge(u.getModelCode(), nz(u.getCost()), BigDecimal::add);
    }
    return m;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static long nzl(Long v) {
    return v == null ? 0L : v;
  }

  private static BigDecimal scale2(BigDecimal v) {
    return nz(v).setScale(2, RoundingMode.HALF_UP);
  }

  private static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  private static double round4(double v) {
    return Math.round(v * 10000.0) / 10000.0;
  }
}
