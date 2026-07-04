package com.ragforge.controller;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.BizException;
import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.vo.AnomalyVo;
import com.ragforge.model.vo.CaseDetailVo;
import com.ragforge.model.vo.CostSummaryVo;
import com.ragforge.model.vo.KpiVo;
import com.ragforge.model.vo.OverviewVo;
import com.ragforge.model.vo.SampleStatsVo;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.service.JudgeQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class JudgeQualityControllerTest {

  @Mock private JudgeQueryService queryService;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private com.ragforge.judge.JudgeBudgetService budgetService;

  private MockMvc mockMvc;
  private JudgeQualityController controller;
  private final com.ragforge.judge.JudgeCostGuardProperties costGuardProperties =
      new com.ragforge.judge.JudgeCostGuardProperties();

  @BeforeEach
  void setUp() {
    controller =
        new JudgeQualityController(
            queryService, kbAccessGuard, knowledgeBaseMapper, costGuardProperties, budgetService);
    mockMvc =
        standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @org.junit.jupiter.api.AfterEach
  void clearHolders() {
    com.ragforge.security.RagAuthContextHolder.clear();
    com.ragforge.security.OrgContextHolder.clear();
    com.ragforge.security.AdminOverrideHolder.clear();
  }

  @Test
  void overview_returnsTypicalPayload() throws Exception {
    when(queryService.overview(eq(7), isNull(), any())).thenReturn(buildOverview());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/overview?days=7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.kpis.overallScore").value(0.72));

    verify(queryService).overview(eq(7), isNull(), any());
  }

  @Test
  void overview_返回4个change字段() throws Exception {
    when(queryService.overview(eq(7), isNull(), any())).thenReturn(buildOverview());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/overview?days=7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kpis.overallChange").value(0.0))
        .andExpect(jsonPath("$.data.kpis.faithfulnessChange").value(0.01))
        .andExpect(jsonPath("$.data.kpis.contextPrecisionChange").value(-0.02))
        .andExpect(jsonPath("$.data.kpis.answerRelevanceChange").value(0.0));
  }

  @Test
  void overview_deniesWhenKbForbidden() throws Exception {
    when(kbAccessGuard.canRead(11L)).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/overview?kbId=11"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    verify(queryService, never()).overview(anyInt(), any(), any());
  }

  @Test
  void byKb_returnsReadableKbStats() throws Exception {
    when(queryService.byKb(eq(7), any())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/by-kb?days=7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    verify(queryService).byKb(eq(7), any());
  }

  @Test
  void byKb_returnsEmptyWhenNoReadableKbs() throws Exception {
    when(queryService.byKb(eq(7), any())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/by-kb?days=7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void worstCases_typicalAndQueryByKb() throws Exception {
    when(queryService.worstCases(eq(5), eq(7), eq(9L), any())).thenReturn(List.of());
    when(kbAccessGuard.canRead(9L)).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/worst-cases?limit=5&days=7&kbId=9"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    verify(queryService).worstCases(5, 7, 9L, Set.of());
  }

  @Test
  void worstCases_withoutKbFiltersByOrgScope() throws Exception {
    when(queryService.worstCases(eq(5), eq(7), isNull(), any())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/worst-cases?limit=5&days=7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    verify(queryService).worstCases(eq(5), eq(7), isNull(), any());
  }

  @Test
  void worstCases_deniesWhenKbForbidden() throws Exception {
    when(kbAccessGuard.canRead(9L)).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/worst-cases?kbId=9"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    verify(queryService, never()).worstCases(anyInt(), anyInt(), any(), any());
  }

  @Test
  void caseDetail_typicalReturns() throws Exception {
    when(queryService.caseDetail(100L)).thenReturn(buildCaseDetail());
    when(kbAccessGuard.canRead(9L)).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.judgeReasoning").value("summary"));
  }

  @Test
  void caseDetail_无权KB_返回403() throws Exception {
    when(queryService.caseDetail(100L)).thenReturn(buildCaseDetail());
    when(kbAccessGuard.canRead(9L)).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void caseDetail_有权KB_返回200() throws Exception {
    when(queryService.caseDetail(100L)).thenReturn(buildCaseDetail());
    when(kbAccessGuard.canRead(9L)).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.kbIds[0]").value(9));
  }

  @Test
  void caseDetail_detailNull_返回404() throws Exception {
    when(queryService.caseDetail(100L)).thenReturn(null);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        // M11：msg 为友好中文，机器码移至 errorCode。
        .andExpect(jsonPath("$.errorCode").value("JUDGE_RESULT_NOT_FOUND"));
  }

  @Test
  void caseDetail_kbIds为空_返回200() throws Exception {
    CaseDetailVo detail = buildCaseDetail();
    detail.setKbIds(List.of());
    when(queryService.caseDetail(100L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    verify(kbAccessGuard, never()).canRead(any());
  }

  @Test
  void caseDetail_kbIds为null_返回200() throws Exception {
    CaseDetailVo detail = buildCaseDetail();
    detail.setKbIds(null);
    when(queryService.caseDetail(100L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    verify(kbAccessGuard, never()).canRead(any());
  }

  @Test
  void caseDetail_notFound() throws Exception {
    when(queryService.caseDetail(100L)).thenThrow(new BizException(404, "JUDGE_RESULT_NOT_FOUND"));

    mockMvc
        .perform(get("/api/v1/evaluation/quality/case/100"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        // M11：msg 为友好中文，机器码移至 errorCode。
        .andExpect(jsonPath("$.errorCode").value("JUDGE_RESULT_NOT_FOUND"));
  }

  @Test
  void cost_typicalPayload() throws Exception {
    when(queryService.cost(eq(30), any())).thenReturn(buildCost());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/cost?days=30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.totalCny").value(12.34));
  }

  @Test
  void cost_defaultDaysFallback() throws Exception {
    when(queryService.cost(eq(30), any())).thenReturn(buildCost());

    mockMvc
        .perform(get("/api/v1/evaluation/quality/cost"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    verify(queryService).cost(eq(30), any());
  }

  @Test
  void budget_组织口径_返回预算已用超支与可编辑() throws Exception {
    com.ragforge.security.RagAuthContextHolder.set(userCtx(42L));
    com.ragforge.security.OrgContextHolder.set(5L);
    when(budgetService.snapshotForOrg(5L))
        .thenReturn(
            new com.ragforge.judge.JudgeBudgetService.BudgetSnapshot(
                new BigDecimal("100.0000"), new BigDecimal("100.0000"), true));

    mockMvc
        .perform(get("/api/v1/evaluation/quality/budget"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.monthlyBudgetCny").value(100.0))
        .andExpect(jsonPath("$.data.monthUsedCny").value(100.0))
        .andExpect(jsonPath("$.data.exceeded").value(true))
        // 普通组织用户不可编辑预算（仅平台管理员）
        .andExpect(jsonPath("$.data.editable").value(false));
  }

  @Test
  void setBudget_普通组织用户_返回403仅平台管理员() throws Exception {
    com.ragforge.security.RagAuthContextHolder.set(userCtx(42L));
    com.ragforge.security.OrgContextHolder.set(5L);

    mockMvc
        .perform(
            put("/api/v1/evaluation/quality/budget")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"monthlyBudgetCny\":100}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("BUDGET_ADMIN_ONLY"));

    verify(budgetService, never()).setBudget(any(), any());
  }

  @Test
  void setBudget_平台管理员_设置成功() throws Exception {
    com.ragforge.security.RagAuthContextHolder.set(adminCtx(1L));
    com.ragforge.security.OrgContextHolder.set(5L);
    when(budgetService.snapshotForOrg(5L))
        .thenReturn(
            new com.ragforge.judge.JudgeBudgetService.BudgetSnapshot(
                new BigDecimal("100.0000"), new BigDecimal("0.0000"), false));

    mockMvc
        .perform(
            put("/api/v1/evaluation/quality/budget")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"monthlyBudgetCny\":100}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.monthlyBudgetCny").value(100.0));

    verify(budgetService).setBudget(eq(5L), eq(new BigDecimal("100")));
  }

  @Test
  void setBudget_非法金额_返回400() throws Exception {
    com.ragforge.security.RagAuthContextHolder.set(adminCtx(1L));
    com.ragforge.security.OrgContextHolder.set(5L);

    mockMvc
        .perform(
            put("/api/v1/evaluation/quality/budget")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"monthlyBudgetCny\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_PARAM:monthlyBudgetCny"));

    verify(budgetService, never()).setBudget(any(), any());
  }

  private static RagAuthContext userCtx(Long userId) {
    return new RagAuthContext(userId, "USER", Set.of(), Set.of(), Set.of(), "USER", String.valueOf(userId));
  }

  private static RagAuthContext adminCtx(Long userId) {
    return new RagAuthContext(userId, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", String.valueOf(userId));
  }

  private OverviewVo buildOverview() {
    OverviewVo vo = new OverviewVo();
    KpiVo kpi = new KpiVo();
    kpi.setOverallScore(new BigDecimal("0.7200"));
    kpi.setOverallChange(BigDecimal.ZERO);
    kpi.setFaithfulness(new BigDecimal("0.7300"));
    kpi.setFaithfulnessChange(new BigDecimal("0.0100"));
    kpi.setContextPrecision(new BigDecimal("0.7100"));
    kpi.setContextPrecisionChange(new BigDecimal("-0.0200"));
    kpi.setAnswerRelevance(new BigDecimal("0.7000"));
    kpi.setAnswerRelevanceChange(BigDecimal.ZERO);
    kpi.setCostLastPeriodCny(new BigDecimal("1.20"));
    kpi.setRetrievalLatencyP95Ms(250);
    vo.setKpis(kpi);

    SampleStatsVo sampleStats = new SampleStatsVo();
    sampleStats.setTotalSamples(50);
    sampleStats.setFailedSamples(2);
    sampleStats.setFailedRate(BigDecimal.valueOf(0.04));
    vo.setSamples(sampleStats);

    AnomalyVo anomaly = new AnomalyVo();
    anomaly.setSeverity("NORMAL");
    anomaly.setOverallDrop(BigDecimal.ZERO);
    anomaly.setReason("stable");
    vo.setAnomaly(anomaly);
    return vo;
  }

  private CaseDetailVo buildCaseDetail() {
    CaseDetailVo vo = new CaseDetailVo();
    vo.setJudgeResultId(100L);
    vo.setKbIds(List.of(9L));
    vo.setQuery("how to sample");
    vo.setAnswer("yes");
    vo.setCreatedAt(LocalDateTime.now());
    vo.setJudgeReasoning("summary");
    vo.setBottleneck("RETRIEVAL");
    return vo;
  }

  private CostSummaryVo buildCost() {
    CostSummaryVo vo = new CostSummaryVo();
    vo.setTotalCny(new BigDecimal("12.34"));
    vo.setDailyAverageCny(new BigDecimal("0.4113"));
    vo.setMonthlyProjectedCny(new BigDecimal("12.34"));
    vo.setTotalCalls(100);
    vo.setFailedCalls(4);
    vo.setCostBySource(
        java.util.Map.of("PRODUCTION", new BigDecimal("10"), "GOLDEN_SET", new BigDecimal("1.2"), "MANUAL", new BigDecimal("1.14")));
    return vo;
  }
}
