<template>
  <div class="page-body quality-page">
    <div class="quality-header">
      <h1 class="quality-title">质量看板</h1>
      <p class="quality-subtitle">LLM-as-Judge 评测质量趋势与成本监控</p>
    </div>

    <div v-if="globalError" class="quality-error-banner">
      {{ globalError }}
    </div>

    <div v-if="anomalyMessage" class="anomaly-banner" :class="anomalyMessage.severityClass">
      <strong>{{ anomalyMessage.level }}</strong>：{{ anomalyMessage.text }}
    </div>

    <section class="quality-toolbar">
      <div class="toolbar-block">
        <label>时间范围</label>
        <div class="toolbar-btns" role="group">
          <button
            v-for="item in dayOptions"
            :key="item"
            :class="['btn-ghost-small', { active: days === item }]"
            @click="setDays(item)"
          >
            {{ item }}天
          </button>
        </div>
      </div>

      <div class="toolbar-block">
        <label>KB 筛选</label>
        <div class="kb-filter-row">
          <input
            v-model="kbIdInput"
            type="text"
            inputmode="numeric"
            placeholder="留空为全部"
            @keyup.enter="applyKbFilter"
          />
          <button class="btn-ghost-small" @click="applyKbFilter">应用</button>
          <button class="btn-ghost-small" @click="clearKbFilter">清除</button>
        </div>
      </div>
    </section>

    <section class="quality-kpi-grid">
      <article class="kpi-card">
        <div class="kpi-title">综合质量</div>
        <div class="kpi-value" :class="scoreClass(kpis.overallScore)">
          {{ formatScore(kpis.overallScore) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.overallChange)">
          {{ formatDelta(kpis.overallChange) }}
        </div>
      </article>
      <article class="kpi-card">
        <div class="kpi-title">答案忠实度</div>
        <div class="kpi-value" :class="scoreClass(kpis.faithfulness)">
          {{ formatScore(kpis.faithfulness) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.faithfulnessChange)">
          {{ formatDelta(kpis.faithfulnessChange) }}
        </div>
      </article>
      <article class="kpi-card">
        <div class="kpi-title">上下文精度</div>
        <div class="kpi-value" :class="scoreClass(kpis.contextPrecision)">
          {{ formatScore(kpis.contextPrecision) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.contextPrecisionChange)">
          {{ formatDelta(kpis.contextPrecisionChange) }}
        </div>
      </article>
      <article class="kpi-card">
        <div class="kpi-title">答案相关性</div>
        <div class="kpi-value" :class="scoreClass(kpis.answerRelevance)">
          {{ formatScore(kpis.answerRelevance) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.answerRelevanceChange)">
          {{ formatDelta(kpis.answerRelevanceChange) }}
        </div>
      </article>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>趋势（{{ days }} 天）</h2>
        <div class="trend-switches">
          <label v-for="metric in metricOptions" :key="metric.key">
            <input type="checkbox" v-model="visibleMetrics" :value="metric.key" />
            {{ metric.label }}
          </label>
        </div>
      </div>
      <div class="panel-body">
        <div v-if="loading.overview" class="state-hint">加载中...</div>
        <div v-else-if="trendPoints.length === 0 || sampleCount <= 0" class="state-hint">暂无评测数据，请检查 Golden Set 是否启用</div>
        <div v-else class="trend-chart-wrap">
          <div class="trend-meta">hover 查看当日样本与分数</div>
          <svg
            class="trend-chart-svg"
            viewBox="0 0 980 280"
            @mouseleave="hoveredPoint = null"
          >
            <line
              v-for="tick in yGrid"
              :key="`y-${tick}`"
              :x1="chartPadding.left"
              :x2="chartInnerRight"
              :y1="yScale(tick)"
              :y2="yScale(tick)"
              stroke="rgba(148,163,184,0.28)"
              stroke-width="1"
            />
            <line
              :x1="chartPadding.left"
              :x2="chartPadding.left"
              :y1="chartPadding.top"
              :y2="chartInnerBottom"
              stroke="rgba(148,163,184,0.3)"
              stroke-width="1"
            />
            <line
              :x1="chartPadding.left"
              :x2="chartInnerRight"
              :y1="chartInnerBottom"
              :y2="chartInnerBottom"
              stroke="rgba(148,163,184,0.3)"
              stroke-width="1"
            />

            <g v-for="metric in visibleSeries" :key="metric.key">
              <path
                :d="metric.path"
                :stroke="metric.color"
                stroke-width="2"
                fill="none"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <circle
                v-for="point in metric.points"
                :key="`p-${metric.key}-${point.index}`"
                :cx="point.x"
                :cy="point.y"
                r="3"
                :fill="metric.color"
                class="trend-dot"
                @mouseenter="hoveredPoint = trendPoints[point.index]"
              />
            </g>

            <g>
              <template v-for="(tick, index) in xLabels" :key="`x-${index}`">
                <text
                  :x="tick.x"
                  :y="chartInnerBottom + 18"
                  text-anchor="middle"
                  fill="var(--text-muted)"
                  font-size="11"
                >
                  {{ tick.label }}
                </text>
              </template>
            </g>
          </svg>

          <div v-if="hoveredPoint" class="trend-hover-card">
            <div><strong>{{ formatDateOnly(hoveredPoint.date) }}</strong></div>
            <div>样本数：{{ hoveredPoint.sampleCount || 0 }}</div>
            <div>综合质量：{{ formatScore(hoveredPoint.overall) }}</div>
            <div>答案忠实度：{{ formatScore(hoveredPoint.faithfulness) }}</div>
            <div>上下文精度：{{ formatScore(hoveredPoint.contextPrecision) }}</div>
            <div>答案相关性：{{ formatScore(hoveredPoint.answerRelevance) }}</div>
          </div>
        </div>
      </div>
    </section>

    <section class="split-panels">
      <article class="panel flex-panel">
        <div class="panel-head"><h2>KB 切片</h2></div>
        <div class="panel-body">
          <div v-if="loading.kb" class="state-hint">加载中...</div>
          <div v-else-if="kbRows.length === 0" class="state-hint">暂无可见评测数据</div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>KB 名</th>
                  <th>分数</th>
                  <th>趋势</th>
                  <th>样本数</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in kbRows"
                  :key="`kb-${row.kbId}`"
                  class="clickable-row"
                  @click="openKb(row.kbId)"
                >
                  <td>{{ row.kbName || `KB ${row.kbId}` }}</td>
                  <td><span :class="scoreClass(row.overallScore)">{{ formatScore(row.overallScore) }}</span></td>
                  <td>
                    <span :class="trendClass(row.trend)">
                      {{ formatSigned(row.trend) }}
                    </span>
                  </td>
                  <td>{{ row.sampleCount || 0 }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </article>

      <article class="panel flex-panel">
        <div class="panel-head"><h2>最差 10 个 case</h2></div>
        <div class="panel-body">
          <div v-if="loading.worst" class="state-hint">加载中...</div>
          <div v-else-if="worstCases.length === 0" class="state-hint">暂无最差样本</div>
          <div v-else class="worst-list">
            <article
              v-for="item in worstCases"
              :key="item.judgeResultId"
              class="worst-item"
              @click="openCase(item.judgeResultId)"
            >
              <div class="worst-query">{{ truncateText(item.query, 72) }}</div>
              <div class="worst-meta">
                <span :class="scoreClass(item.overallScore)">评分 {{ formatScore(item.overallScore) }}</span>
                <span>•</span>
                <span>{{ formatDateTime(item.createdAt) }}</span>
                <span>•</span>
                <span class="top-issue">{{ item.topIssue || '暂无问题摘要' }}</span>
              </div>
            </article>
          </div>
        </div>
      </article>
    </section>

    <section class="panel cost-panel">
      <div class="panel-head">
        <h2>成本分析</h2>
      </div>
      <div class="panel-body">
        <div v-if="loading.cost" class="state-hint">加载中...</div>
        <div v-else-if="!cost.totalCalls && sampleCount === 0" class="state-hint">暂无评测调用数据</div>
        <div v-else class="cost-section">
          <div class="cost-cards">
            <div class="cost-card">
              <div class="cost-label">累计调用次数</div>
              <div class="cost-value">{{ cost.totalCalls || 0 }}</div>
            </div>
            <div class="cost-card">
              <div class="cost-label">累计成本（CNY）</div>
              <div class="cost-value">¥{{ formatMoney(cost.totalCny) }}</div>
            </div>
            <div class="cost-card">
              <div class="cost-label">日均成本</div>
              <div class="cost-value">¥{{ formatMoney(cost.dailyAverageCny) }}</div>
            </div>
            <div class="cost-card">
              <div class="cost-label">月度预测</div>
              <div class="cost-value">¥{{ formatMoney(cost.monthlyProjectedCny) }}</div>
            </div>
          </div>

          <div class="cost-stack-wrap">
            <div
              class="cost-stack"
              v-for="item in costStacks"
              :key="item.key"
              :style="{ width: `${item.pct}%`, background: item.color }"
            >
              <span>{{ item.label }}</span>
            </div>
          </div>
            <div class="cost-legend">
              <span v-for="item in costStacks" :key="`${item.key}-legend`" class="legend-item">
                <i :style="{ background: item.color }" />
                {{ item.label }}：¥{{ formatMoney(item.value) }}
              </span>
            </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchOverview, fetchByKb, fetchWorstCases, fetchCost } from '../api/quality'
import { useAuth } from '../composables/useAuth'

const { clearSession } = useAuth()
const router = useRouter()
const route = useRoute()

const dayOptions = [7, 30, 90]
const metricOptions = [
  { key: 'overall', label: '总体', color: '#0f766e' },
  { key: 'faithfulness', label: '忠实度', color: '#2563eb' },
  { key: 'contextPrecision', label: '上下文精度', color: '#d97706' },
  { key: 'answerRelevance', label: '答案相关性', color: '#8b5cf6' },
]

const loading = reactive({
  overview: false,
  kb: false,
  worst: false,
  cost: false,
})

const days = ref(7)
const kbId = ref(null)
const kbIdInput = ref('')

const kpis = reactive({
  overallScore: null,
  faithfulness: null,
  contextPrecision: null,
  answerRelevance: null,
  overallChange: null,
  faithfulnessChange: null,
  contextPrecisionChange: null,
  answerRelevanceChange: null,
})

const trendPoints = ref([])
const hoveredPoint = ref(null)
const visibleMetrics = ref(['overall', 'faithfulness', 'contextPrecision', 'answerRelevance'])
const kbRows = ref([])
const worstCases = ref([])
const cost = ref({
  totalCny: 0,
  dailyAverageCny: 0,
  monthlyProjectedCny: 0,
  totalCalls: 0,
  failedCalls: 0,
  costBySource: {},
})
const anomaly = ref(null)
const globalError = ref('')
const sampleCount = ref(0)

const chartWidth = 980
const chartHeight = 280
const chartPadding = { top: 12, right: 12, bottom: 34, left: 44 }
const chartInnerRight = chartWidth - chartPadding.right
const chartInnerBottom = chartHeight - chartPadding.bottom

const yGrid = [0, 0.2, 0.4, 0.6, 0.8, 1]

const anomalyMessage = computed(() => {
  if (!anomaly.value) return null
  const severity = anomaly.value.severity
  if (!severity || severity === 'NORMAL') return null
  return {
    text: anomaly.value.reason || '异常阈值触发',
    level: severity,
    severityClass: severity === 'CRITICAL' ? 'anomaly-critical' : 'anomaly-warn',
  }
})

const normalizedDays = computed(() => Number(days.value) || 7)
const queryParams = computed(() => {
  const params = { days: normalizedDays.value }
  if (kbId.value != null) {
    params.kbId = kbId.value
  }
  return params
})

const xLabels = computed(() => {
  if (!trendPoints.value.length) return []
  const total = trendPoints.value.length
  return trendPoints.value.map((item, index) => {
    const x = chartPadding.left + ((chartInnerRight - chartPadding.left) * index) / Math.max(1, total - 1)
    return { x, label: formatDateOnly(item.date), index }
  })
})

const visibleSeries = computed(() => {
  return metricOptions
    .filter((metric) => visibleMetrics.value.includes(metric.key))
    .map((metric) => {
      const pts = trendPoints.value
        .map((pt, index) => ({ pt, index }))
        .filter((item) => Number.isFinite(item.pt[metric.key]))
        .map((item) => ({
          x: xForIndex(item.index, trendPoints.value.length),
          y: yForValue(item.pt[metric.key]),
          index: item.index,
          value: item.pt[metric.key],
        }))

      if (!pts.length) {
        return Object.assign({}, metric, { points: [], path: '' })
      }

      const d = pts
        .map((pt, i) => `${i === 0 ? 'M' : 'L'} ${pt.x} ${pt.y}`)
        .join(' ')

      return Object.assign({}, metric, { points: pts, path: d })
    })
})

function unwrapResponse(resp) {
  const body = resp?.data != null ? resp.data : resp
  if (body && typeof body === 'object' && body.code === 200 && 'data' in body) {
    return body.data
  }
  return body || {}
}

function parseHttpError(error) {
  const status = error?.response?.status || error?.status
  if (status === 401) {
    clearSession()
    router.push('/login')
    return '登录已失效，请重新登录'
  }
  if (status === 403) {
    return '无权访问该 KB'
  }
  if (status >= 500) {
    return '数据加载失败，请刷新重试'
  }
  return '请求失败，请稍后重试'
}

function formatScore(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return num.toFixed(2)
}

function formatSigned(value) {
  const num = Number(value)
  if (!Number.isFinite(num) || num === 0) return '→ 0.00'
  return num > 0 ? `↑ +${num.toFixed(2)}` : `↓ ${num.toFixed(2)}`
}

function formatDelta(value) {
  return formatSigned(value)
}

function formatMoney(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '0.00'
  return num.toFixed(2)
}

function formatDateOnly(value) {
  if (!value) return '--'
  const date = new Date(`${value}T00:00:00`)
  if (Number.isNaN(date.getTime())) return '--'
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function formatDateTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function truncateText(text, maxLength) {
  const source = text || ''
  return source.length > maxLength ? `${source.slice(0, maxLength)}...` : source
}

function scoreClass(value) {
  if (value === null || value === undefined || value === '') return 'score-muted'
  const num = Number(value)
  if (!Number.isFinite(num)) return 'score-muted'
  if (num <= 0 && (sampleCount.value || 0) <= 0) return 'score-muted'
  if (num >= 0.8) return 'score-green'
  if (num >= 0.6) return 'score-amber'
  return 'score-red'
}

function trendClass(value) {
  const num = Number(value)
  if (!Number.isFinite(num) || num === 0) return 'trend-equal'
  return num > 0 ? 'trend-up' : 'trend-down'
}

function xForIndex(index, total) {
  if (total <= 1) return chartPadding.left
  return chartPadding.left + ((chartInnerRight - chartPadding.left) * index) / (total - 1)
}

function yForValue(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return chartInnerBottom
  const safe = Math.max(0, Math.min(1, num))
  return chartInnerBottom - safe * (chartInnerBottom - chartPadding.top)
}

function setDays(value) {
  if (days.value === value) return
  days.value = value
  const targetQuery = Object.assign({}, route.query, { days: value })
  if (kbId.value != null) {
    targetQuery.kbId = kbId.value
  } else {
    delete targetQuery.kbId
  }
  router.push({ path: '/evaluation/quality', query: targetQuery })
}

function applyKbFilter() {
  const parsed = Number.parseInt(kbIdInput.value, 10)
  if (Number.isFinite(parsed)) {
    kbId.value = parsed
    router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value, kbId: parsed } })
  } else {
    kbId.value = null
    router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value } })
  }
}

function clearKbFilter() {
  kbIdInput.value = ''
  kbId.value = null
  router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value } })
}

function openKb(targetKbId) {
  if (!targetKbId) return
  router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value, kbId: targetKbId } })
}

function openCase(id) {
  if (!id) return
  router.push(`/evaluation/quality/case/${id}`)
}

function deriveChange(values) {
  if (values.length < 2) return null
  const first = Number(values[0])
  const last = Number(values[values.length - 1])
  if (!Number.isFinite(first) || !Number.isFinite(last) || first === 0) return null
  return Number(((last - first) / first).toFixed(4))
}

async function loadOverview() {
  loading.overview = true
  try {
    const response = await fetchOverview(queryParams.value.days, queryParams.value.kbId)
    const data = unwrapResponse(response)
    const kpi = data?.kpis || {}
    const trend = Array.isArray(data?.trend) ? data.trend : []

    kpis.overallScore = kpi.overallScore
    kpis.faithfulness = kpi.faithfulness
    kpis.contextPrecision = kpi.contextPrecision
    kpis.answerRelevance = kpi.answerRelevance

    if (Number.isFinite(Number(kpi.overallChange))) {
      kpis.overallChange = Number(kpi.overallChange)
      kpis.faithfulnessChange = Number.isFinite(Number(kpi.faithfulnessChange)) ? Number(kpi.faithfulnessChange) : deriveChange(trend.map((p) => p.overall))
      kpis.contextPrecisionChange = Number.isFinite(Number(kpi.contextPrecisionChange)) ? Number(kpi.contextPrecisionChange) : deriveChange(trend.map((p) => p.contextPrecision))
      kpis.answerRelevanceChange = Number.isFinite(Number(kpi.answerRelevanceChange)) ? Number(kpi.answerRelevanceChange) : deriveChange(trend.map((p) => p.answerRelevance))
    } else {
      kpis.overallChange = deriveChange(trend.map((p) => p.overall))
      kpis.faithfulnessChange = deriveChange(trend.map((p) => p.faithfulness))
      kpis.contextPrecisionChange = deriveChange(trend.map((p) => p.contextPrecision))
      kpis.answerRelevanceChange = deriveChange(trend.map((p) => p.answerRelevance))
    }

    trendPoints.value = trend.map((item) => ({
      date: item.date,
      overall: item.overall,
      faithfulness: item.faithfulness,
      contextPrecision: item.contextPrecision,
      answerRelevance: item.answerRelevance,
      sampleCount: item.sampleCount || 0,
    }))

    sampleCount.value = data?.samples?.sampleCount != null ? Number(data.samples.sampleCount) : trendPoints.value.reduce((acc, item) => acc + Number(item.sampleCount || 0), 0)
    anomaly.value = data?.anomaly || null
  } catch (error) {
    globalError.value = parseHttpError(error)
    trendPoints.value = []
  } finally {
    loading.overview = false
  }
}

async function loadKbRows() {
  loading.kb = true
  try {
    const response = await fetchByKb(days.value)
    const data = unwrapResponse(response)
    if (!Array.isArray(data)) {
      kbRows.value = []
      return
    }
    kbRows.value = data
      .map((row) => ({
        kbId: row.kbId,
        kbName: row.kbName || '',
        sampleCount: row.sampleCount || 0,
        trend: row.trend,
        overallScore: row.overallScore,
      }))
      .sort((a, b) => Number(a.overallScore || 0) - Number(b.overallScore || 0))
  } catch (error) {
    globalError.value = parseHttpError(error)
    kbRows.value = []
  } finally {
    loading.kb = false
  }
}

async function loadWorstCases() {
  loading.worst = true
  try {
    const response = await fetchWorstCases(10, days.value, kbId.value)
    const data = unwrapResponse(response)
    worstCases.value = Array.isArray(data)
      ? data.map((item) => ({
          judgeResultId: item.judgeResultId,
          query: item.query,
          overallScore: item.overallScore,
          createdAt: item.createdAt,
          topIssue: item.topIssue,
        }))
      : []
  } catch (error) {
    globalError.value = parseHttpError(error)
    worstCases.value = []
  } finally {
    loading.worst = false
  }
}

async function loadCost() {
  loading.cost = true
  try {
    const response = await fetchCost(days.value)
    cost.value = unwrapResponse(response) || {
      totalCny: 0,
      dailyAverageCny: 0,
      monthlyProjectedCny: 0,
      totalCalls: 0,
      failedCalls: 0,
      costBySource: {},
    }
    if (!cost.value.costBySource || typeof cost.value.costBySource !== 'object') {
      cost.value.costBySource = {}
    }
  } catch (error) {
    globalError.value = parseHttpError(error)
    cost.value = {
      totalCny: 0,
      dailyAverageCny: 0,
      monthlyProjectedCny: 0,
      totalCalls: 0,
      failedCalls: 0,
      costBySource: {},
    }
  } finally {
    loading.cost = false
  }
}

function loadAll() {
  globalError.value = ''
  Promise.allSettled([
    loadOverview(),
    loadKbRows(),
    loadWorstCases(),
    loadCost(),
  ])
}

const costStacks = computed(() => {
  const sourceMap = {
    PRODUCTION: '#3b82f6',
    GOLDEN_SET: '#06b6d4',
    MANUAL: '#8b5cf6',
  }
  const sourceOrder = ['PRODUCTION', 'GOLDEN_SET', 'MANUAL']
  const entries = sourceOrder.map((source) => ({
    key: source,
    label: source,
    value: Number(cost.value?.costBySource?.[source] || 0),
    color: sourceMap[source],
  }))
  const total = entries.reduce((acc, item) => acc + item.value, 0)
  if (total <= 0) {
    return [{ key: 'EMPTY', label: '无调用', value: 1, pct: 100, color: '#e2e8f0' }]
  }
  return entries.map((item) => Object.assign({}, item, { pct: (item.value / total) * 100 }))
})

watch(
  () => route.query,
  (nextQuery) => {
    const qDays = Number(nextQuery?.days)
    const qKb = nextQuery?.kbId

    if (Number.isFinite(qDays) && qDays > 0) {
      days.value = qDays
    }
    if (qKb === undefined) {
      kbId.value = null
      kbIdInput.value = ''
    } else {
      const parsed = Number.parseInt(qKb, 10)
      if (Number.isFinite(parsed)) {
        kbId.value = parsed
        kbIdInput.value = String(parsed)
      }
    }
    loadAll()
  },
  { immediate: true }
)
</script>

<style scoped>
.quality-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.quality-header {
  margin-bottom: 6px;
}

.quality-title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.quality-subtitle {
  color: var(--text-muted);
  margin-top: 4px;
  font-size: 13px;
}

.panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.panel-head,
.panel-body {
  padding: 12px 14px;
}

.panel-head {
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-head h2 {
  font-size: 14px;
  color: #334155;
}

.quality-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 14px;
}

.toolbar-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.toolbar-btns {
  display: flex;
  gap: 8px;
}

.kb-filter-row {
  display: flex;
  gap: 8px;
}

.toolbar-block input {
  width: 180px;
  border: 1px solid var(--border);
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff;
  color: var(--text);
  transition: border-color 0.15s ease;
}
.toolbar-block input:focus {
  outline: none;
  border-color: var(--blue);
}

.quality-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.kpi-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
}

.kpi-title {
  color: var(--text-muted);
  font-size: 12px;
  margin-bottom: 6px;
}

.kpi-value {
  font-size: 30px;
  font-weight: 700;
  margin-bottom: 4px;
}

.kpi-trend {
  font-size: 12px;
}

.score-green { color: #16a34a; }
.score-amber { color: #d97706; }
.score-red { color: #dc2626; }
.score-muted { color: #cbd5e1; }

.trend-up { color: #16a34a; }
.trend-down { color: #dc2626; }
.trend-equal { color: var(--text-muted); }

.trend-switches {
  display: flex;
  gap: 12px;
  font-size: 12px;
}

.trend-chart-wrap { position: relative; min-height: 248px; }
.trend-meta { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }

.trend-chart-svg {
  width: 100%;
  height: 220px;
  overflow: visible;
}

.trend-dot { cursor: pointer; }

.trend-hover-card {
  position: absolute;
  right: 0;
  top: 10px;
  width: 214px;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text);
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.1);
  display: grid;
  gap: 3px;
}

.split-panels {
  display: grid;
  grid-template-columns: 1.25fr 1fr;
  gap: 14px;
}

.flex-panel {
  min-height: 380px;
  display: flex;
  flex-direction: column;
}

.table-wrap { overflow: auto; }
.clickable-row { cursor: pointer; }
.clickable-row:hover { background: rgba(59,130,246,0.06); }

.worst-list { display: flex; flex-direction: column; gap: 8px; }
.worst-item {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px;
  cursor: pointer;
}

.worst-item:hover { background: rgba(15,23,42,0.02); }
.worst-query {
  font-weight: 500;
  color: #0f172a;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.worst-meta {
  color: var(--text-muted);
  font-size: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.top-issue { color: #64748b; }

.cost-panel { padding-bottom: 8px; }
.cost-section { display: flex; flex-direction: column; gap: 12px; }
.cost-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.cost-card {
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px;
}
.cost-label { font-size: 12px; color: var(--text-muted); }
.cost-value { font-size: 20px; color: #0f172a; font-weight: 700; margin-top: 2px; }

.cost-stack-wrap {
  display: flex;
  height: 26px;
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid var(--border);
}

.cost-stack {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #fff;
  min-width: 1%;
}

.cost-legend {
  display: flex;
  gap: 14px;
  color: var(--text-muted);
  font-size: 12px;
  flex-wrap: wrap;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-item i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.quality-error-banner,
.anomaly-banner {
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 13px;
}

.quality-error-banner {
  background: #fee2e2;
  color: #991b1b;
  border: 1px solid #fecaca;
}

.anomaly-banner {
  color: #92400e;
  border: 1px solid #fed7aa;
  background: #fffbeb;
}

.anomaly-banner.anomaly-critical {
  color: #7f1d1d;
  border-color: #fecaca;
  background: #fee2e2;
}

.btn-ghost-small {
  appearance: none;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 14px;
  font-size: 13px;
  color: var(--text);
  cursor: pointer;
  transition: all 0.15s ease;
  line-height: 1.4;
}
.btn-ghost-small:hover {
  border-color: var(--blue);
  color: var(--blue);
  background: #f0f7ff;
}
.btn-ghost-small.active {
  border-color: var(--blue);
  color: var(--blue);
  background: #dbeafe;
  font-weight: 600;
}

@media (max-width: 1280px) {
  .quality-kpi-grid,
  .cost-cards {
    grid-template-columns: repeat(2, 1fr);
  }

  .split-panels {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .quality-toolbar {
    flex-direction: column;
  }

  .toolbar-btns {
    flex-wrap: wrap;
  }

  .trend-chart-svg {
    height: 220px;
  }

  .kb-filter-row {
    flex-wrap: wrap;
  }

  .quality-kpi-grid,
  .cost-cards {
    grid-template-columns: 1fr;
  }
}
</style>
