<template>
  <div class="page-body">
    <div v-if="isPersonal" class="upgrade-banner">
      <span class="ub-ico">⬆</span>
      <span class="ub-txt">
        <b>当前为个人组织</b> —— 需要和同事协作？升级为团队组织即可邀请成员、共享知识库，<b>组织 ID 不变、知识库零迁移</b>。
      </span>
      <button class="btn btn-sm ub-btn-todo" @click="onUpgradeTodo" title="该功能待开发">升级到团队组织</button>
    </div>

    <div v-if="isPlatform" class="breakglass-banner">
      <span class="bg-ico">🛡</span>
      <span class="bg-txt">
        <b>破玻璃提权已激活（X-Admin-Override）</b> —— 全平台聚合视图，本次访问全程审计留痕；SYSTEM 库与明文凭证不可得。
      </span>
    </div>

    <div class="scope-line">
      <span class="scope-ico">📊</span>
      <span>统计范围：{{ scopeText }} · 口径：近 7 天</span>
    </div>

    <div class="section-title">核心指标 · 近 7 天</div>

    <div class="metrics-grid">
      <!-- 资产规模 -->
      <div class="metric-card accent-blue">
        <div class="metric-top">
          <span class="metric-label">资产规模</span>
          <span class="metric-chip chip-blue">📚</span>
        </div>
        <div class="metric-value">{{ formatNumber(metrics.chunkCount) }} <span class="metric-unit">Chunk</span></div>
        <div class="metric-subs">
          <div class="metric-sub"><span>文档</span><b>{{ formatNumber(metrics.documentCount) }}</b></div>
          <div class="metric-sub"><span>知识库</span><b>{{ metrics.kbCount }}</b></div>
          <div class="metric-sub">
            <span>入库成功率</span>
            <b :class="{ pos: ingestTotal > 0 }">{{ ingestTotal > 0 ? formatPercent(metrics.ingestSuccessRate) : '—' }}</b>
          </div>
        </div>
      </div>
      <!-- 检索质量 -->
      <div class="metric-card accent-green">
        <div class="metric-top">
          <span class="metric-label">检索质量</span>
          <span class="metric-chip chip-green">🎯</span>
        </div>
        <div class="metric-value metric-value--hit">{{ formatPercent(metrics.zeroResultRate) }}<span class="metric-unit"> 零结果</span></div>
        <div class="metric-subs">
          <div class="metric-sub"><span>平均召回条数</span><b>{{ formatDecimal(metrics.avgRecallCount) }}</b></div>
          <div class="metric-sub">
            <span>平均 rerank 分 <i class="tag-mini">仅精排</i></span><b>{{ formatDecimal2(metrics.avgRerankScore) }}</b>
          </div>
          <div class="metric-sub"><span>Query 改写率</span><b>{{ formatPercent(metrics.rewriteRate) }}</b></div>
        </div>
      </div>
      <!-- 运行健康 -->
      <div class="metric-card accent-amber">
        <div class="metric-top">
          <span class="metric-label">运行健康</span>
          <span class="metric-chip chip-amber">⚡</span>
        </div>
        <div class="metric-value">{{ formatLatency(metrics.p95LatencyMs) }} <span class="metric-unit">P95</span></div>
        <div class="metric-subs">
          <div class="metric-sub"><span>P50 延迟</span><b>{{ formatLatency(metrics.p50LatencyMs) }}</b></div>
          <div class="metric-sub">
            <span>检索成功率</span>
            <b :class="{ pos: metrics.periodApiCalls > 0 }">{{ metrics.periodApiCalls > 0 ? formatPercent(metrics.searchSuccessRate) : '—' }}</b>
          </div>
          <div class="metric-sub"><span>检索请求数</span><b>{{ formatNumber(metrics.periodApiCalls) }}</b></div>
        </div>
      </div>
      <!-- 成本消耗 -->
      <div class="metric-card accent-violet metric-card--link" @click="$router.push('/models')">
        <div class="metric-top">
          <span class="metric-label">成本消耗</span>
          <span class="metric-chip chip-violet">💰</span>
        </div>
        <div class="metric-value">{{ formatYuan(metrics.totalCost) }}</div>
        <div class="metric-subs">
          <div class="metric-sub"><span>Token 总量</span><b>{{ formatTokens(metrics.tokenTotal) }}</b></div>
          <div class="metric-sub"><span>embedding / rerank</span><b>{{ formatYuan(metrics.embeddingCost) }} / {{ formatYuan(metrics.rerankCost) }}</b></div>
          <div class="metric-sub"><span>LLM(改写+评测)</span><b>{{ formatYuan(metrics.llmCost) }}</b></div>
        </div>
      </div>
    </div>


    <div class="dash-row2">
      <!-- 检索趋势 · 近 7 天 -->
      <div class="card trend-panel">
        <div class="card-header">
          <span class="card-title">检索趋势 · 近 7 天</span>
          <span class="card-sub">请求数 / P95 延迟</span>
        </div>
        <div v-if="trendStats.hasData" class="panel-body">
          <div class="trend-summary">
            请求峰值 <b>{{ trendStats.peak }}/日</b>
            <span class="sep">·</span>
            P95 区间 <b>{{ trendStats.p95Min }} ~ {{ trendStats.p95Max }}ms</b>
          </div>
          <div class="trend-chart">
            <div
              v-for="(p, i) in metrics.retrievalTrend"
              :key="i"
              class="trend-bar-wrap"
              :title="`${p.date}：${p.count} 次 · P95 ${p.p95LatencyMs}ms`"
            >
              <div class="trend-bar" :style="{ height: trendBarHeight(p.count) }"></div>
              <span class="trend-x">{{ p.date }}</span>
            </div>
          </div>
        </div>
        <div v-else class="panel-empty">近 7 天暂无检索记录</div>
      </div>

      <!-- 入库处理健康度 -->
      <div class="card ingest-panel">
        <div class="card-header">
          <span class="card-title">入库处理健康度</span>
          <span class="card-sub">{{ formatNumber(ingestTotal) }} 文档</span>
        </div>
        <div v-if="ingestTotal > 0" class="panel-body">
          <div class="ingest-bar">
            <span class="seg seg-done" :style="{ width: ingestPct.completed }"></span>
            <span class="seg seg-proc" :style="{ width: ingestPct.processing }"></span>
            <span class="seg seg-queue" :style="{ width: ingestPct.queued }"></span>
            <span class="seg seg-fail" :style="{ width: ingestPct.failed }"></span>
          </div>
          <div class="ingest-legend">
            <span><i class="dot dot-done"></i>已完成 {{ metrics.ingestCompleted }}</span>
            <span><i class="dot dot-proc"></i>处理中 {{ metrics.ingestProcessing }}</span>
            <span><i class="dot dot-queue"></i>排队 {{ metrics.ingestQueued }}</span>
            <span><i class="dot dot-fail"></i>失败 {{ metrics.ingestFailed }}</span>
          </div>
          <div
            v-if="metrics.ingestFailed > 0"
            class="ingest-cta fail"
            @click="$router.push({ path: '/knowledge', query: { parseStatus: 'failed' } })"
          >
            ⚠️ {{ metrics.ingestFailed }} 个文档解析失败 · 一键重试 →
          </div>
          <div v-else class="ingest-cta ok">✓ 入库链路健康，无失败文档</div>
        </div>
        <div v-else class="panel-empty">当前范围内暂无文档</div>
      </div>
    </div>

    <div v-if="!isPersonal && !isPlatform" class="card member-panel">
      <div class="card-header">
        <span class="card-title">组织成员</span>
        <button class="btn btn-secondary btn-sm" @click="$router.push('/orgs')">管理成员 →</button>
      </div>
      <div class="member-overview">
        <div class="ava-pile">
          <span
            v-for="m in members.slice(0, 6)"
            :key="m.userId"
            class="m-ava"
            :style="{ background: avaColor(m.userId) }"
            :title="m.displayName"
          >{{ initial(m.displayName) }}</span>
          <span v-if="memberStats.total > 6" class="m-ava ava-more">+{{ memberStats.total - 6 }}</span>
        </div>
        <div class="member-stats">
          <span>共 <b>{{ memberStats.total }}</b> 人</span>
          <span class="sep">·</span>
          <span>{{ memberStats.owner }} OWNER</span>
          <span>{{ memberStats.admin }} ADMIN</span>
          <span>{{ memberStats.member }} MEMBER</span>
        </div>
      </div>
    </div>

    <div class="card activity-panel">
      <div class="card-header">
        <span class="card-title">最近操作</span>
      </div>
      <div class="activity-list">
        <div
          class="activity-item"
          :class="{ error: item.type === 'error' }"
          v-for="(item, index) in metrics.recentActivities"
          :key="index"
        >
          <span class="activity-dot" />
          <span class="activity-time">{{ item.time }}</span>
          <span class="activity-msg">{{ item.message }}</span>
          <button
            v-if="item.retryable"
            class="btn btn-ghost btn-sm activity-retry"
            @click="retryDocument(item.docId)"
          >重试</button>
        </div>
        <div v-if="!metrics.recentActivities || metrics.recentActivities.length === 0" class="activity-empty">
          暂无最近操作
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed, watch } from 'vue'
import { getDashboardMetrics } from '../api/metrics'
import { reprocessDocument } from '../api/document'
import { useOrg } from '../composables/useOrg'
import { listMembers } from '../api/org'
import { useToast } from '../composables/useToast'

const { current, isPersonal, isPlatform, load: loadOrgs } = useOrg()
const toast = useToast()
const loading = ref(false)
const lastUpdated = ref('')

function onUpgradeTodo() {
  // 升级到团队组织功能待开发：按钮置灰，点击仅提示。
  toast.info('该功能待开发')
}

// ===== 组织成员概览（仅团队组织展示，复用 /orgs/{id}/members） =====
const members = ref([])
const memberStats = computed(() => {
  const s = { total: members.value.length, owner: 0, admin: 0, member: 0 }
  for (const m of members.value) {
    if (m.role === 'OWNER') s.owner += 1
    else if (m.role === 'ADMIN') s.admin += 1
    else s.member += 1
  }
  return s
})
const MEMBER_PALETTE = ['#0f1f3d', '#2563eb', '#15803d', '#7c3aed', '#db2777', '#0ea5e9', '#f59e0b']
function avaColor(id) {
  return MEMBER_PALETTE[Math.abs(Number(id) || 0) % MEMBER_PALETTE.length]
}
function initial(name) {
  return (name || '?').trim().charAt(0).toUpperCase()
}
async function loadMembers() {
  if (isPersonal.value || isPlatform.value || !current.value.id) {
    members.value = []
    return
  }
  try {
    const res = await listMembers(current.value.id)
    members.value = Array.isArray(res?.data) ? res.data : []
  } catch {
    members.value = []
  }
}
const metrics = reactive({
  kbCount: 0,
  documentCount: 0,
  chunkCount: 0,
  todayApiCalls: 0,
  avgLatencyMs: 0,
  hitRate: 0,
  zeroResultRate: 0,
  avgRecallCount: 0,
  p95LatencyMs: 0,
  rewriteRate: 0,
  ingestSuccessRate: 0,
  p50LatencyMs: 0,
  periodApiCalls: 0,
  searchSuccessRate: 0,
  avgRerankScore: 0,
  totalCost: 0,
  tokenTotal: 0,
  embeddingCost: 0,
  rerankCost: 0,
  llmCost: 0,
  ingestCompleted: 0,
  ingestProcessing: 0,
  ingestQueued: 0,
  ingestFailed: 0,
  retrievalTrend: [],
  recentActivities: [],
})

const scopeText = computed(() => {
  if (isPlatform.value) return '全平台所有组织（仅超级管理员，破玻璃生效中）'
  if (isPersonal.value) return '仅你个人组织内的库与检索'
  return '本组织全部库 + 全体成员检索'
})

const ingestTotal = computed(
  () =>
    metrics.ingestCompleted +
    metrics.ingestProcessing +
    metrics.ingestQueued +
    metrics.ingestFailed,
)
const ingestPct = computed(() => {
  const t = ingestTotal.value || 1
  const pct = (n) => `${(n / t) * 100}%`
  return {
    completed: pct(metrics.ingestCompleted),
    processing: pct(metrics.ingestProcessing),
    queued: pct(metrics.ingestQueued),
    failed: pct(metrics.ingestFailed),
  }
})

const trendStats = computed(() => {
  const t = metrics.retrievalTrend || []
  if (!t.length) return { hasData: false }
  const counts = t.map((p) => Number(p.count) || 0)
  const p95s = t.map((p) => Number(p.p95LatencyMs) || 0).filter((v) => v > 0)
  return {
    hasData: true,
    peak: Math.max(...counts),
    p95Min: p95s.length ? Math.min(...p95s) : 0,
    p95Max: p95s.length ? Math.max(...p95s) : 0,
  }
})
function trendBarHeight(count) {
  const peak = trendStats.value.peak || 1
  const ratio = Math.min(1, (Number(count) || 0) / peak)
  return `${Math.max(6, ratio * 100)}%`
}

async function loadMetrics() {
  loading.value = true
  try {
    const res = await getDashboardMetrics()
    const data = res.data ?? {}
    metrics.kbCount = data.kbCount ?? 0
    metrics.documentCount = data.documentCount ?? 0
    metrics.chunkCount = data.chunkCount ?? 0
    metrics.todayApiCalls = data.todayApiCalls ?? 0
    metrics.avgLatencyMs = data.avgLatencyMs ?? 0
    metrics.hitRate = data.hitRate ?? 0
    metrics.zeroResultRate = data.zeroResultRate ?? 0
    metrics.avgRecallCount = data.avgRecallCount ?? 0
    metrics.p95LatencyMs = data.p95LatencyMs ?? 0
    metrics.rewriteRate = data.rewriteRate ?? 0
    metrics.ingestSuccessRate = data.ingestSuccessRate ?? 0
    metrics.p50LatencyMs = data.p50LatencyMs ?? 0
    metrics.periodApiCalls = data.periodApiCalls ?? 0
    metrics.searchSuccessRate = data.searchSuccessRate ?? 0
    metrics.avgRerankScore = data.avgRerankScore ?? 0
    metrics.totalCost = data.totalCost ?? 0
    metrics.tokenTotal = data.tokenTotal ?? 0
    metrics.embeddingCost = data.embeddingCost ?? 0
    metrics.rerankCost = data.rerankCost ?? 0
    metrics.llmCost = data.llmCost ?? 0
    metrics.ingestCompleted = data.ingestCompleted ?? 0
    metrics.ingestProcessing = data.ingestProcessing ?? 0
    metrics.ingestQueued = data.ingestQueued ?? 0
    metrics.ingestFailed = data.ingestFailed ?? 0
    metrics.retrievalTrend = data.retrievalTrend ?? []
    metrics.recentActivities = data.recentActivities ?? []
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } finally {
    loading.value = false
  }
}

async function retryDocument(docId) {
  if (!docId) {
    console.log('retryDocument: missing docId')
    return
  }
  await reprocessDocument(docId)
  await loadMetrics()
}

function formatNumber(n) {
  const num = Number(n)
  if (Number.isNaN(num)) return '0'
  return num.toLocaleString()
}

function formatChunkCount(n) {
  const num = Number(n)
  if (Number.isNaN(num)) return '0'
  if (num >= 1000) {
    const k = num / 1000
    return `${k.toFixed(k >= 100 ? 0 : 1)}K`
  }
  return `${num}`
}

function formatLatency(ms) {
  const num = Number(ms)
  if (Number.isNaN(num) || num <= 0) return '—'
  if (num >= 1000) return `${(num / 1000).toFixed(1)}s`
  return `${num}ms`
}

function formatHitRate(rate) {
  const num = Number(rate)
  if (Number.isNaN(num) || num <= 0) return '—'
  return `${(num * 100).toFixed(1)}%`
}

function formatPercent(rate) {
  const num = Number(rate)
  if (Number.isNaN(num) || num < 0) return '—'
  return `${(num * 100).toFixed(1)}%`
}

function formatDecimal(n) {
  const num = Number(n)
  if (Number.isNaN(num) || num <= 0) return '—'
  return num.toFixed(1)
}

function formatDecimal2(n) {
  const num = Number(n)
  if (Number.isNaN(num) || num <= 0) return '—'
  return num.toFixed(2)
}

function formatYuan(n) {
  const num = Number(n)
  if (Number.isNaN(num)) return '¥0.00'
  return `¥${num.toFixed(2)}`
}

function formatTokens(n) {
  const num = Number(n)
  if (Number.isNaN(num) || num <= 0) return '0'
  if (num >= 1_000_000) return `${(num / 1_000_000).toFixed(1)}M`
  if (num >= 1000) return `${Math.round(num / 1000)}K`
  return `${num}`
}

onMounted(() => {
  loadMetrics()
})
// 组织成员概览随当前组织联动：org 列表异步加载完成或切换组织后 current.id 变化即重新拉取，
// 避免首屏 org 尚未就绪时 loadMembers 因 current.id 为空而显示「0 人」（且此前无重试）。
watch(() => current.value?.id, () => loadMembers(), { immediate: true })
</script>

<style scoped>
.refresh-ico { display: inline-block; font-size: 13px; }
.refresh-ico.spin { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 顶部状态条（teleport 到顶栏右侧） ===== */
.dash-updated {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.16);
}

/* ===== 指标卡 ===== */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.metric-card {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 18px 20px 20px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
/* 顶部极细高光，提升精致度 */
.metric-card::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: linear-gradient(90deg, transparent, rgba(37, 99, 235, 0.16), transparent);
  opacity: 0;
  transition: opacity 0.18s ease;
}
.metric-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-md);
  border-color: #d8e2ef;
}
.metric-card:hover::after { opacity: 1; }

.metric-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.metric-label { font-size: 12.5px; color: var(--text-muted); font-weight: 600; letter-spacing: 0.2px; }

.metric-chip {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}
.chip-blue { background: var(--primary-soft); box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.1); }
.chip-green { background: #ecfdf5; box-shadow: inset 0 0 0 1px rgba(16, 185, 129, 0.12); }
.chip-amber { background: #fffbeb; box-shadow: inset 0 0 0 1px rgba(245, 158, 11, 0.14); }
.chip-cyan { background: #ecfeff; box-shadow: inset 0 0 0 1px rgba(6, 182, 212, 0.14); }
.chip-violet { background: #f5f3ff; box-shadow: inset 0 0 0 1px rgba(124, 58, 237, 0.14); }

/* 四象限：主数值单位 + 次级统计行 */
.metric-unit { font-size: 14px; font-weight: 600; color: var(--text-muted); }
.metric-value--muted { color: var(--text-muted); font-size: 22px; }
.metric-subs { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--border); display: flex; flex-direction: column; gap: 7px; }
.metric-sub { display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: var(--text-muted); }
.metric-sub b { color: var(--slate); font-weight: 600; font-variant-numeric: tabular-nums; }
.metric-card--link { cursor: pointer; }

.metric-value {
  font-size: 34px;
  font-weight: 700;
  color: var(--navy);
  letter-spacing: -0.8px;
  margin-top: 16px;
  line-height: 1.05;
  font-variant-numeric: tabular-nums;
}
.metric-value--hit { color: var(--green); }

.metric-desc { font-size: 12px; color: var(--text-muted); margin-top: 8px; }

/* ===== 口径行 + 区块标题 ===== */
.scope-line {
  display: flex; align-items: center; gap: 6px;
  font-size: 12.5px; color: var(--text-muted); margin-bottom: 6px;
}
.scope-ico { font-size: 13px; }
.section-title { font-size: 14px; font-weight: 700; color: var(--navy); margin: 4px 0 12px; }

/* 卡片左侧彩色强调边 */
.metric-card.accent-blue::before,
.metric-card.accent-green::before,
.metric-card.accent-amber::before,
.metric-card.accent-violet::before {
  content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
}
.metric-card.accent-blue::before { background: var(--primary); }
.metric-card.accent-green::before { background: #10b981; }
.metric-card.accent-amber::before { background: #f59e0b; }
.metric-card.accent-violet::before { background: #7c3aed; }

.metric-sub b.pos { color: var(--green); }
.tag-mini {
  font-style: normal; font-size: 10px; color: var(--text-muted);
  border: 1px solid var(--border); border-radius: 999px; padding: 0 6px; margin-left: 4px;
}

/* ===== 第二行：检索趋势 + 入库健康度 ===== */
.dash-row2 {
  display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 16px;
}
.panel-empty { padding: 28px; text-align: center; color: var(--text-muted); font-size: 13px; }
.panel-body { padding: 14px 20px 18px; }

.trend-summary { font-size: 12.5px; color: var(--text-muted); margin: 0 0 14px; }
.trend-summary b { color: var(--slate); font-variant-numeric: tabular-nums; }
.trend-summary .sep { margin: 0 8px; color: var(--border); }
.trend-chart {
  display: flex; align-items: flex-end; gap: 10px; height: 120px; padding-top: 6px;
}
.trend-bar-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px; height: 100%; justify-content: flex-end; }
.trend-bar {
  width: 60%; max-width: 30px; min-height: 6px;
  background: linear-gradient(180deg, var(--primary), #93c5fd);
  border-radius: 6px 6px 0 0; transition: height 0.25s ease;
}
.trend-x { font-size: 10.5px; color: var(--text-muted); font-variant-numeric: tabular-nums; }

.ingest-bar {
  display: flex; height: 12px; border-radius: 999px; overflow: hidden;
  background: var(--light); margin: 6px 0 14px;
}
.ingest-bar .seg { height: 100%; transition: width 0.25s ease; }
.seg-done { background: #10b981; }
.seg-proc { background: #06b6d4; }
.seg-queue { background: #f59e0b; }
.seg-fail { background: #ef4444; }
.ingest-legend {
  display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--text-muted);
}
.ingest-legend .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 5px; }
.dot-done { background: #10b981; }
.dot-proc { background: #06b6d4; }
.dot-queue { background: #f59e0b; }
.dot-fail { background: #ef4444; }
.ingest-cta { margin-top: 16px; font-size: 13px; font-weight: 600; }
.ingest-cta.fail { color: #dc2626; cursor: pointer; }
.ingest-cta.fail:hover { text-decoration: underline; }
.ingest-cta.ok { color: var(--green); }


/* ===== 升档横幅（个人空间） ===== */
.upgrade-banner { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; border-radius: 14px; padding: 12px 16px; background: linear-gradient(95deg, #eef4ff, #f7faff); border: 1px solid var(--primary-border); }
.upgrade-banner .ub-ico { width: 34px; height: 34px; border-radius: 10px; background: var(--primary); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 17px; flex-shrink: 0; }
.upgrade-banner .ub-txt { flex: 1; font-size: 13px; color: var(--slate); } .upgrade-banner .ub-txt b { color: var(--navy); }
/* 升级到团队组织功能待开发：置灰但仍可点击（点击后 toast 提示）。 */
.upgrade-banner .ub-btn-todo { background: #e5e7eb; color: #9ca3af; border: 1px solid #d1d5db; cursor: not-allowed; box-shadow: none; }
.upgrade-banner .ub-btn-todo:hover { background: #e5e7eb; color: #9ca3af; }

/* ===== 破玻璃横幅 + 权限矩阵（全平台视图） ===== */
.breakglass-banner { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; border-radius: 14px; padding: 12px 16px; background: linear-gradient(95deg, #fff7ed, #fffbeb); border: 1px solid #fed7aa; }
.breakglass-banner .bg-ico { width: 34px; height: 34px; border-radius: 10px; background: var(--amber, #f59e0b); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 17px; flex-shrink: 0; }
.breakglass-banner .bg-txt { font-size: 13px; color: var(--slate); } .breakglass-banner .bg-txt b { color: var(--navy); }
.card-sub { font-size: 12px; color: var(--text-muted); }

/* 卡片区块统一上间距，与四象限/趋势区一致 */
.member-panel, .activity-panel { margin-top: 16px; }

/* ===== 组织成员概览（定高，不随人数变长） ===== */
.member-panel .card-header { display: flex; align-items: center; justify-content: space-between; }
.member-overview { padding: 12px 16px 16px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.ava-pile { display: flex; }
.ava-pile .m-ava { margin-left: -10px; border: 2px solid #fff; }
.ava-pile .m-ava:first-child { margin-left: 0; }
.m-ava { width: 34px; height: 34px; border-radius: 9px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 700; }
.ava-more { background: #e2e8f0; color: var(--gray); font-size: 12px; }
.member-stats { font-size: 13px; color: var(--text-muted); display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.member-stats b { color: var(--navy); font-weight: 700; }
.member-stats .sep { color: #dbe2ea; }

/* ===== 最近操作 ===== */
.activity-list { padding: 6px 8px 10px; }

.activity-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--gray);
  padding: 9px 12px;
  border-radius: 8px;
}
.activity-item:hover { background: #f8fafc; }

.activity-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-teal);
  flex-shrink: 0;
}
.activity-item.error .activity-dot { background: var(--red); }
.activity-item.error .activity-msg { color: var(--red); }

.activity-time { color: var(--text-muted); font-variant-numeric: tabular-nums; font-size: 12px; flex-shrink: 0; }
.activity-msg { flex: 1; min-width: 0; }
.activity-retry { margin-left: auto; color: var(--primary); }
.activity-retry:hover { background: var(--primary-soft); color: var(--primary-hover); }

.activity-empty { padding: 28px 0; text-align: center; color: var(--text-muted); font-size: 13px; }

/* ===== 移动端 ===== */
@media (max-width: 768px) {
  .metrics-grid { grid-template-columns: repeat(2, 1fr); gap: 12px; }
  .metric-value { font-size: 24px; }
  .dash-row2 { grid-template-columns: 1fr; }
}

@media (max-width: 420px) {
  .metrics-grid { grid-template-columns: 1fr; }
  .activity-time { display: none; }
}
</style>
