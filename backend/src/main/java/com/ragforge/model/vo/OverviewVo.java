package com.ragforge.model.vo;

import java.util.List;

public class OverviewVo {

  private KpiVo kpis;
  private List<TrendPointVo> trend;
  private AnomalyVo anomaly;
  private SampleStatsVo samples;

  public KpiVo getKpis() {
    return kpis;
  }

  public void setKpis(KpiVo kpis) {
    this.kpis = kpis;
  }

  public List<TrendPointVo> getTrend() {
    return trend;
  }

  public void setTrend(List<TrendPointVo> trend) {
    this.trend = trend;
  }

  public AnomalyVo getAnomaly() {
    return anomaly;
  }

  public void setAnomaly(AnomalyVo anomaly) {
    this.anomaly = anomaly;
  }

  public SampleStatsVo getSamples() {
    return samples;
  }

  public void setSamples(SampleStatsVo samples) {
    this.samples = samples;
  }
}

