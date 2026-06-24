<template>
  <div class="page-body quality-page">
    <p class="quality-subtitle">LLM-as-Judge 评测质量趋势与成本监控</p>

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

      <div class="toolbar-spacer" />

      <div class="toolbar-block toolbar-action">
        <label>&nbsp;</label>
        <button class="btn-settings" @click="openSamplingDrawer">
          <span class="btn-settings-icon">⚙</span>
          <span>设置</span>
        </button>
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

    <div v-if="showSamplingDrawer" class="drawer-mask" @click.self="closeSamplingDrawer">
      <aside class="settings-drawer">
        <div class="drawer-head">
          <div>
            <h2>抽样与 Golden Set</h2>
            <p>控制线上 LLM-as-Judge 抽样率；超过 10% 保存时需要二次确认。</p>
          </div>
          <button class="drawer-close" @click="closeSamplingDrawer">关闭</button>
        </div>

        <div v-if="settingsError" class="quality-error-banner">{{ settingsError }}</div>

        <section class="drawer-section">
          <h3>全局抽样率</h3>
          <div class="rate-row">
            <input v-model.number="globalRatePercent" type="range" min="0" max="10" step="0.5" />
            <strong>{{ globalRatePercent.toFixed(1) }}%</strong>
          </div>
          <label class="inline-check">
            <input v-model="globalSamplingEnabled" type="checkbox" />
            启用全局抽样
          </label>
          <div v-if="globalRatePercent > 5" class="cost-warning">
            当前抽样率超过 5%，请结合调用量评估月度成本。
          </div>
          <div class="drawer-action-row">
            <button
              class="btn-save-config"
              :class="{ 'is-saving': savingSampling }"
              :disabled="savingSampling"
              @click="saveGlobalSampling"
            >
              <span v-if="savingSampling" class="btn-save-spinner" aria-hidden="true" />
              <svg v-else class="btn-save-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <path
                  d="M4 3.5h8.5L16 7v9.5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1Z"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linejoin="round"
                />
                <path d="M8 3.5v4h4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M6 13.5h8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
              </svg>
              <span>{{ savingSampling ? '保存中...' : '保存全局配置' }}</span>
            </button>
          </div>
        </section>

        <section class="drawer-section">
          <h3>KB 覆盖</h3>
          <div class="kb-override-form">
            <select v-model="kbOverrideForm.scopeId">
              <option :value="null" disabled>选择知识库</option>
              <option v-for="kb in kbOptions" :key="kb.id" :value="kb.id">{{ kb.name || `KB ${kb.id}` }}</option>
            </select>
            <input v-model.number="kbOverrideForm.ratePercent" type="number" min="0" max="10" step="0.5" />
            <label><input v-model="kbOverrideForm.enabled" type="checkbox" /> 启用</label>
            <button class="btn-ghost-small" :disabled="savingSampling" @click="saveKbOverride">保存覆盖</button>
          </div>
          <div v-if="kbSamplingConfigs.length === 0" class="state-hint">暂无 KB 单独覆盖</div>
          <div v-else class="override-list">
            <div v-for="item in kbSamplingConfigs" :key="item.id" class="override-item">
              <span>{{ kbName(item.scopeId) }}</span>
              <span>{{ ratePercent(item.sampleRate).toFixed(1) }}%</span>
              <span>{{ item.enabled ? '启用' : '停用' }}</span>
              <button class="link-button danger" @click="removeSampling(item.id)">删除</button>
            </div>
          </div>
        </section>

        <section class="drawer-section">
          <h3>Golden Set 回放</h3>
          <p>当前启用题数：<strong>{{ goldenEnabledCount }}</strong></p>
          <button class="btn-save-config btn-save-config--secondary" :disabled="replayingGolden" @click="replayGoldenNow">
            {{ replayingGolden ? '已提交...' : '立即回放' }}
          </button>
          <p class="muted">手动触发会异步排队执行，每题间隔 500ms。</p>
        </section>

        <section class="drawer-section">
          <h3>月度预算</h3>
          <p>当前预算：<strong>¥{{ monthlyBudgetCny }}</strong></p>
          <p class="muted">预算配置只读，如需调整请找架构师修改部署环境变量。</p>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  deleteSamplingConfig,
  fetchByKb,
  fetchCost,
  fetchGoldenSetEnabledCount,
  fetchOverview,
  fetchWorstCases,
  listSamplingConfigs,
  replayGoldenSetNow,
  upsertSamplingConfig,
} from '../api/quality'
import { listKb } from '../api/kb'
import { useAuth } from '../composables/useAuth'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { useToast } from '../composables/useToast'

const toast = useToast()

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
const showSamplingDrawer = ref(false)
const samplingConfigs = ref([])
const kbOptions = ref([])
const globalRatePercent = ref(1)
const globalSamplingEnabled = ref(true)
const goldenEnabledCount = ref(0)
const savingSampling = ref(false)
const replayingGolden = ref(false)
const settingsError = ref('')
const monthlyBudgetCny = ref(200)
const kbOverrideForm = reactive({
  scopeId: null,
  ratePercent: 1,
  enabled: true,
})

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
  if (Number.isInteger(kbId.value) && kbId.value > 0) {
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

const kbSamplingConfigs = computed(() =>
  samplingConfigs.value
    .filter((item) => item.scopeType === 'KB')
    .sort((a, b) => Number(a.scopeId || 0) - Number(b.scopeId || 0))
)

function unwrapResponse(resp) {
  const body = resp?.data != null ? resp.data : resp
  if (body && typeof body === 'object' && body.code === 200 && 'data' in body) {
    return body.data
  }
  return body || {}
}

async function openSamplingDrawer() {
  showSamplingDrawer.value = true
  await loadSamplingSettings()
}

function closeSamplingDrawer() {
  showSamplingDrawer.value = false
  settingsError.value = ''
}

async function loadSamplingSettings() {
  settingsError.value = ''
  try {
    const [configsResp, kbResp, goldenResp] = await Promise.all([
      listSamplingConfigs(),
      listKb(),
      fetchGoldenSetEnabledCount(),
    ])
    samplingConfigs.value = unwrapResponse(configsResp) || []
    kbOptions.value = unwrapResponse(kbResp) || []
    goldenEnabledCount.value = Number(unwrapResponse(goldenResp) || 0)
    const globalConfig = samplingConfigs.value.find((item) => item.scopeType === 'GLOBAL')
    if (globalConfig) {
      globalRatePercent.value = ratePercent(globalConfig.sampleRate)
      globalSamplingEnabled.value = globalConfig.enabled !== false
    }
  } catch (error) {
    settingsError.value = parseHttpError(error)
  }
}

async function saveGlobalSampling() {
  await saveSampling({
    scopeType: 'GLOBAL',
    sampleRate: percentToRate(globalRatePercent.value),
    enabled: globalSamplingEnabled.value,
  })
}

async function saveKbOverride() {
  if (!kbOverrideForm.scopeId) {
    settingsError.value = '请选择知识库'
    return
  }
  await saveSampling({
    scopeType: 'KB',
    scopeId: kbOverrideForm.scopeId,
    sampleRate: percentToRate(kbOverrideForm.ratePercent),
    enabled: kbOverrideForm.enabled,
  })
}

async function saveSampling(payload) {
  const rate = Number(payload.sampleRate)
  if (!Number.isFinite(rate) || rate < 0 || rate > 1) {
    settingsError.value = '抽样率必须在 0% 到 100% 之间'
    return
  }
  let confirmed = false
  if (rate > 0.1) {
    const ok = await confirmDialog({
      title: '抽样率较高',
      message: '抽样率超过 10%，确认保存？',
      variant: 'warning',
    })
    if (!ok) return
    confirmed = true
  }
  savingSampling.value = true
  settingsError.value = ''
  try {
    await upsertSamplingConfig(Object.assign({}, payload, { confirmed }))
    await loadSamplingSettings()
  } catch (error) {
    settingsError.value = error?.response?.data?.message || parseHttpError(error)
  } finally {
    savingSampling.value = false
  }
}

async function removeSampling(id) {
  const ok = await confirmDialog({
    title: '删除抽样覆盖',
    message: '确定删除该抽样覆盖？',
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteSamplingConfig(id)
    await loadSamplingSettings()
    toast.success('抽样覆盖已删除')
  } catch {
    // 全局拦截器已 toast
  }
}

async function replayGoldenNow() {
  replayingGolden.value = true
  settingsError.value = ''
  try {
    await replayGoldenSetNow({ limit: 100 })
    await loadSamplingSettings()
  } catch (error) {
    settingsError.value = parseHttpError(error)
  } finally {
    setTimeout(() => {
      replayingGolden.value = false
    }, 1000)
  }
}

function ratePercent(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num * 100 : 0
}

function percentToRate(value) {
  const pct = Number(value)
  if (!Number.isFinite(pct)) return 0
  return Math.max(0, Math.min(100, pct)) / 100
}

function kbName(id) {
  const kb = kbOptions.value.find((item) => item.id === id)
  return kb?.name || `KB ${id}`
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

    sampleCount.value =
        data?.samples?.totalSamples != null
            ? Number(data.samples.totalSamples)
            : data?.samples?.sampleCount != null
                ? Number(data.samples.sampleCount)
                : trendPoints.value.reduce((acc, item) => acc + Number(item.sampleCount || 0), 0)
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

.quality-subtitle {
  color: var(--text-muted);
  margin: 0 0 2px 0;
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
  align-items: flex-end;
  gap: 18px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}

.toolbar-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.toolbar-block > label {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 500;
}

.toolbar-spacer {
  flex: 1 1 auto;
  min-width: 0;
}

.toolbar-action {
  justify-content: flex-end;
}

.toolbar-btns {
  display: flex;
  gap: 6px;
}

.kb-filter-row {
  display: flex;
  gap: 6px;
}

.btn-settings {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(180deg, #3b82f6, #2563eb);
  color: #fff;
  border: 1px solid #2563eb;
  border-radius: 8px;
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 1px 2px rgba(37, 99, 235, 0.2);
}

.btn-settings:hover {
  background: linear-gradient(180deg, #2563eb, #1d4ed8);
  border-color: #1d4ed8;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.3);
}

.btn-settings-icon {
  font-size: 14px;
  line-height: 1;
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
  gap: 12px;
}

.kpi-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px 18px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.kpi-card:hover {
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
  transform: translateY(-1px);
}

.kpi-title {
  color: var(--text-muted);
  font-size: 12px;
  margin-bottom: 8px;
  letter-spacing: 0.02em;
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

.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(15, 23, 42, 0.36);
  display: flex;
  justify-content: flex-end;
}

.settings-drawer {
  width: min(520px, 100vw);
  height: 100%;
  overflow: auto;
  background: #fff;
  border-left: 1px solid var(--border);
  box-shadow: -16px 0 40px rgba(15, 23, 42, 0.16);
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 12px;
}

.drawer-head h2 {
  font-size: 18px;
  color: #0f172a;
}

.drawer-head p,
.muted {
  color: var(--text-muted);
  font-size: 12px;
  margin-top: 4px;
}

.drawer-close,
.link-button {
  border: none;
  background: transparent;
  color: var(--blue);
  cursor: pointer;
}

.link-button.danger {
  color: #dc2626;
}

.drawer-section {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.drawer-section h3 {
  font-size: 14px;
  color: #334155;
}

.rate-row,
.kb-override-form,
.override-item,
.inline-check {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rate-row input[type="range"] {
  flex: 1;
}

.kb-override-form {
  flex-wrap: wrap;
}

.kb-override-form select,
.kb-override-form input[type="number"] {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 8px;
}

.override-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.override-item {
  justify-content: space-between;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
}

.cost-warning {
  border: 1px solid #fed7aa;
  background: #fffbeb;
  color: #92400e;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
}

.drawer-action-row {
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.btn-save-config {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 10px 18px;
  border: 1px solid #0d9488;
  border-radius: 10px;
  background: linear-gradient(180deg, #14b8a6 0%, #0f766e 100%);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow:
    0 1px 2px rgba(15, 118, 110, 0.2),
    0 4px 14px rgba(15, 118, 110, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.18);
  transition: transform 0.15s ease, box-shadow 0.15s ease, filter 0.15s ease;
}

.btn-save-config:hover:not(:disabled) {
  filter: brightness(1.04);
  transform: translateY(-1px);
  box-shadow:
    0 2px 4px rgba(15, 118, 110, 0.22),
    0 8px 20px rgba(15, 118, 110, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.btn-save-config:active:not(:disabled) {
  transform: translateY(0);
  filter: brightness(0.98);
  box-shadow:
    0 1px 2px rgba(15, 118, 110, 0.2),
    inset 0 1px 2px rgba(0, 0, 0, 0.08);
}

.btn-save-config:disabled {
  opacity: 0.72;
  cursor: not-allowed;
  transform: none;
}

.btn-save-config.is-saving {
  background: linear-gradient(180deg, #5eead4 0%, #14b8a6 100%);
}

.btn-save-config--secondary {
  width: auto;
  align-self: flex-start;
  background: linear-gradient(180deg, #3b82f6, #2563eb);
  border-color: #2563eb;
  box-shadow:
    0 1px 2px rgba(37, 99, 235, 0.2),
    0 4px 14px rgba(37, 99, 235, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.18);
}

.btn-save-config--secondary:hover:not(:disabled) {
  box-shadow:
    0 2px 4px rgba(37, 99, 235, 0.24),
    0 8px 20px rgba(37, 99, 235, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.btn-save-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  opacity: 0.95;
}

.btn-save-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: save-spin 0.65s linear infinite;
  flex-shrink: 0;
}

@keyframes save-spin {
  to { transform: rotate(360deg); }
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
