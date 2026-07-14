<template>
  <div class="page-body quality-page">
    <Teleport to="#topbar-left">
      <span class="topbar-divider" />
      <span class="topbar-subtitle">LLM-as-Judge 评测质量趋势与成本监控</span>
    </Teleport>
    <Teleport to="#topbar-right">
      <ElevationToggle page-name="质量看板" @change="loadAll" />
    </Teleport>

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
        <label>知识库筛选</label>
        <div class="kb-filter-row">
          <select class="kb-filter-select" :value="kbId ?? ''" @change="onKbFilterChange">
            <option value="">全部知识库</option>
            <option v-for="kb in kbFilterOptions" :key="kb.id" :value="kb.id">
              {{ kb.name || kbDisplayName(kb.id) }}
            </option>
          </select>
          <button class="btn btn-secondary btn-sm" @click="clearKbFilter">清除</button>
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
        <div class="kpi-title">综合质量（Composite）</div>
        <div class="kpi-value" :class="scoreClass(kpis.overallScore)">
          {{ formatScore(kpis.overallScore) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.overallChange)">
          {{ formatDelta(kpis.overallChange) }}
        </div>
      </article>
      <article class="kpi-card">
        <div class="kpi-title">答案忠实度（Faithfulness）</div>
        <div class="kpi-value" :class="scoreClass(kpis.faithfulness)">
          {{ formatScore(kpis.faithfulness) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.faithfulnessChange)">
          {{ formatDelta(kpis.faithfulnessChange) }}
        </div>
      </article>
      <article class="kpi-card">
        <div class="kpi-title">上下文精度（Context Precision）</div>
        <div class="kpi-value" :class="scoreClass(kpis.contextPrecision)">
          {{ formatScore(kpis.contextPrecision) }}
        </div>
        <div class="kpi-trend" :class="trendClass(kpis.contextPrecisionChange)">
          {{ formatDelta(kpis.contextPrecisionChange) }}
        </div>
      </article>
      <article class="kpi-card">
        <div class="kpi-title">答案相关性（Answer Relevance）</div>
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
        <div v-else-if="sampleCount <= 0 && trendPoints.length === 0" class="state-hint">暂无评测数据。质量指标来自线上流量抽样判分，或由管理员触发黄金集回放，产生样本后将自动展示</div>
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
              :y1="yForValue(tick)"
              :y2="yForValue(tick)"
              stroke="rgba(148,163,184,0.28)"
              stroke-width="1"
            />
            <text
              v-for="tick in yGrid"
              :key="`yl-${tick}`"
              :x="chartPadding.left - 8"
              :y="yForValue(tick) + 4"
              text-anchor="end"
              fill="var(--text-muted)"
              font-size="11"
            >
              {{ tick.toFixed(1) }}
            </text>
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
        <div class="panel-head"><h2>知识库排行</h2></div>
        <div class="panel-body">
          <div v-if="loading.kb" class="state-hint">加载中...</div>
          <div v-else-if="kbRows.length === 0" class="state-hint">暂无可见评测数据</div>
          <div v-else class="table-wrap">
            <table class="data-table kb-slice-table">
              <thead>
                <tr>
                  <th class="col-name">知识库</th>
                  <th class="col-score">分数</th>
                  <th class="col-trend">趋势</th>
                  <th class="col-count">样本数</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in kbRows"
                  :key="`kb-${row.kbId}`"
                  class="clickable-row"
                  @click="openKb(row.kbId)"
                >
                  <td class="col-name" :title="row.kbName || kbDisplayName(row.kbId)">
                    <span class="kb-name-text">{{ row.kbName || kbDisplayName(row.kbId) }}</span>
                  </td>
                  <td class="col-score"><span :class="scoreClass(row.overallScore)">{{ formatScore(row.overallScore) }}</span></td>
                  <td class="col-trend">
                    <span :class="trendClass(row.trend)">
                      {{ formatSigned(row.trend) }}
                    </span>
                  </td>
                  <td class="col-count">{{ row.sampleCount || 0 }}</td>
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
              <div class="worst-query">{{ truncateText(item.query, 50) }}</div>
              <div class="worst-meta">
                <span :class="scoreClass(item.overallScore)">评分 {{ formatScore(item.overallScore) }}</span>
                <span>•</span>
                <span>{{ formatDateTime(item.createdAt) }}</span>
                <span>•</span>
                <span class="top-issue">{{ formatTopIssue(item.topIssue) }}</span>
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
              :class="{ 'cost-stack--narrow': item.pct < 12 }"
              :style="{ width: `${item.pct}%`, background: item.color }"
              :title="`${item.label}：¥${formatMoney(item.value)}`"
            >
              <span v-if="item.pct >= 12">{{ item.label }}</span>
            </div>
          </div>
          <div class="cost-legend">
            <div v-for="item in costStacks" :key="`${item.key}-legend`" class="legend-card">
              <span class="legend-dot" :style="{ background: item.color }" />
              <span class="legend-label">{{ item.label }}</span>
              <span class="legend-value">¥{{ formatMoney(item.value) }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <div v-if="showSamplingDrawer" class="drawer-mask" @click.self="closeSamplingDrawer">
      <aside class="settings-drawer">
        <div class="drawer-head">
          <div>
            <h2>质量评测设置</h2>
            <p>配置本组织如何评测应答质量：① 线上抽样（被动监控）② 黄金集回放（主动验证）。评测由 AI 评审打分，会产生少量成本。</p>
          </div>
          <button
            class="drawer-close"
            type="button"
            aria-label="关闭"
            title="关闭"
            @click="closeSamplingDrawer"
          >
            <svg viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">
              <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </button>
        </div>

        <div v-if="settingsError" class="quality-error-banner">{{ settingsError }}</div>

        <section class="drawer-section">
          <h3>① 线上质量抽样</h3>
          <p class="muted golden-empty-hint">
            从本组织的线上应答里按知识库抽样，交 AI 评审打分持续监控质量。未列出的知识库默认不抽样。
          </p>
          <table v-if="kbSamplingConfigs.length" class="sampling-table">
            <thead>
              <tr><th>知识库</th><th>抽样率</th><th>状态</th><th class="st-a" /></tr>
            </thead>
            <tbody>
              <tr v-for="item in kbSamplingConfigs" :key="item.id">
                <td class="st-name">{{ kbName(item.scopeId) }}</td>
                <td class="st-rate">{{ ratePercent(item.sampleRate).toFixed(1) }}%</td>
                <td>
                  <button
                    type="button"
                    class="switch"
                    :class="{ 'switch--on': item.enabled }"
                    role="switch"
                    :aria-checked="item.enabled ? 'true' : 'false'"
                    :aria-label="item.enabled ? '停用抽样' : '启用抽样'"
                    :disabled="togglingSamplingId === item.id || !canManageOrg"
                    :title="canManageOrg ? '' : '仅组织所有者 / 管理员可配置'"
                    @click="toggleSampling(item)"
                  >
                    <span class="switch-knob" />
                  </button>
                </td>
                <td class="st-a">
                  <button class="link-button" :disabled="!canManageOrg" @click="editSampling(item)">编辑</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="state-hint">还没有为任何知识库设置抽样</div>

          <div v-if="showKbForm" class="kb-override-form">
            <select v-model="kbOverrideForm.scopeId" :disabled="editingSamplingId != null">
              <option :value="null" disabled>选择知识库</option>
              <option v-for="kb in kbOptions" :key="kb.id" :value="kb.id">{{ kb.name || kbDisplayName(kb.id) }}</option>
            </select>
            <input v-model.number="kbOverrideForm.ratePercent" type="number" min="0" max="10" step="0.5" />
            <label><input v-model="kbOverrideForm.enabled" type="checkbox" /> 启用</label>
            <button class="btn btn-secondary btn-sm" :disabled="savingOverride" @click="saveKbOverride">保存</button>
            <button v-if="editingSamplingId != null" class="link-button danger" @click="removeSampling(editingSamplingId)">删除</button>
            <button class="link-button" @click="closeKbForm">取消</button>
          </div>
          <button
            v-else-if="canManageOrg"
            class="add-sampling-btn"
            @click="openNewKbForm"
          >
            ＋ 为知识库设置抽样
          </button>
          <p v-if="!canManageOrg" class="muted golden-empty-hint">抽样配置仅组织所有者 / 管理员可修改。</p>
          <p class="sampling-cost-hint">
            💰 本组织评审成本：本月已用 <strong>¥{{ formatMoney(cost.totalCny) }}</strong>，按当前用量预计本月
            <strong>¥{{ formatMoney(cost.monthlyProjectedCny) }}</strong>。抽样越高越全面，成本也越高。
          </p>
        </section>

        <section class="drawer-section">
          <h3>② 黄金集回归测试</h3>

          <!-- 平台管理员 · 全平台视图：平台基准卡片 -->
          <div v-if="isPlatformView" class="platform-baseline">
            <div class="pb-head">
              <span class="pb-title">🎯 平台基准</span>
              <span class="pb-badge">Core Set</span>
            </div>
            <p class="pb-count"><strong>{{ goldenEnabledCount }}</strong> <span>道 · 固定，不可增删</span></p>
            <p class="muted">绑定系统组织的冻结基线库；回放进「平台健康视图」，不混入租户看板。</p>
            <button
              class="btn-save-config btn-save-config--secondary"
              :disabled="replayingGolden"
              @click="replayPlatformBaseline"
            >
              {{ replayingGolden ? '任务进行中...' : '平台基准回放' }}
            </button>
          </div>

          <!-- 组织视图：本组织黄金集回归 -->
          <template v-else>
            <p class="muted golden-empty-hint">
              从「评测数据集」勾选黄金问题作为本组织的质量基准；改动分块/模型/内容后一键回放，直接点亮本组织质量看板。
            </p>
            <p>本组织已启用：<strong>{{ goldenEnabledCount }}</strong> 道黄金题</p>
            <button
              class="btn-save-config btn-save-config--secondary"
              :disabled="replayingGolden || goldenEnabledCount <= 0 || !canManageOrg || budgetExceeded"
              :title="replayButtonTitle"
              @click="replayGoldenNow"
            >
              {{ replayingGolden ? '任务进行中...' : `立即回放本组织 ${goldenEnabledCount} 题` }}
            </button>
            <p v-if="!canManageOrg" class="muted golden-empty-hint">仅组织所有者 / 管理员可触发回放。</p>
            <p v-else-if="budgetExceeded" class="budget-over-hint">
              本月评测额度已用完，回放已暂停；请联系平台管理员调高配额或下月再试。
            </p>
            <p v-else-if="goldenEnabledCount <= 0" class="muted golden-empty-hint">
              本组织还没有黄金题，请先到「评测数据集」勾选要纳入回归的问题。
            </p>
            <p v-else class="muted">
              异步执行，每题间隔 500ms · 结果进本组织质量看板 · 单次最多 50 题、每 5 分钟一次 · 点击需二次确认。
            </p>
          </template>
        </section>

        <section class="drawer-section">
          <h3>
            本月评测配额
            <span v-if="judgeBudget.platformShared" class="budget-scope">全平台共享</span>
            <button
              v-if="judgeBudget.editable && !editingBudget"
              class="link-button budget-edit-btn"
              @click="startEditBudget"
            >
              编辑
            </button>
          </h3>
          <div v-if="editingBudget" class="budget-edit-form">
            <span>配额 ¥</span>
            <input v-model.number="budgetInput" type="number" min="1" step="10" />
            <span>/ 月</span>
            <button class="btn btn-secondary btn-sm" :disabled="savingBudget" @click="saveBudget">保存</button>
            <button class="link-button" @click="editingBudget = false">取消</button>
          </div>
          <template v-else>
            <div class="budget-bar">
              <div class="budget-fill" :class="`budget-fill--${budgetLevel}`" :style="{ width: budgetPercent + '%' }" />
            </div>
            <div class="budget-meta">
              <span :class="{ 'budget-over': budgetExceeded }">已用 <strong>¥{{ formatMoney(judgeBudget.monthUsedCny) }}</strong></span>
              <span class="muted">配额 ¥{{ formatMoney(judgeBudget.monthlyBudgetCny) }} / 月</span>
            </div>
            <p v-if="budgetExceeded" class="budget-over-hint">本月评测额度已用完，回放已暂停；请联系平台管理员调高配额或下月再试。</p>
            <p v-else class="muted">线上抽样与黄金集回放共用本组织此预算；用完后组织回放会被拦截。</p>
          </template>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  deleteSamplingConfig,
  fetchByKb,
  fetchCost,
  fetchJudgeBudget,
  updateJudgeBudget,
  fetchGoldenSetEnabledCount,
  fetchOverview,
  fetchWorstCases,
  listSamplingConfigs,
  replayGoldenSetNow,
  replayGoldenSetForOrg,
  upsertSamplingConfig,
} from '../api/quality'
import { listKb } from '../api/kb'
import { bottleneckLabel, resolveHttpError } from '../api/error-messages'
import { useAuth } from '../composables/useAuth'
import { useOrg } from '../composables/useOrg'
import { useElevation } from '../composables/useElevation'
import { confirm as confirmDialog } from '../composables/useConfirm'
import ElevationToggle from '../components/ElevationToggle.vue'
import { useToast } from '../composables/useToast'

const toast = useToast()

const { clearSession, ragRole } = useAuth()
// 全局抽样率与月度预算仅平台管理员可改;非管理员时全局区控件置灰(与后端 SAMPLING_GLOBAL_ADMIN_ONLY 一致)。
const isPlatformAdmin = computed(() => ragRole.value === 'ADMIN')
// 组织级回放/抽样/预算配置权限：严格按当前组织角色（所有者/管理员），去掉平台管理员在任意组织的兜底。
// 平台管理员在"自己只是普通成员"的组织里不放行；提权（破玻璃）时落系统组织 myRole=OWNER 天然覆盖。
const { current: currentOrg } = useOrg()
// 「全平台」口径由提权（破玻璃）驱动，而非已下线的全局全平台视图。
const { active: isPlatform } = useElevation()
const canManageOrg = computed(() => ['OWNER', 'ADMIN'].includes(currentOrg.value?.myRole))
// 提权（破玻璃）：② 段展示「平台基准」卡片而非组织黄金集。
const isPlatformView = computed(() => isPlatformAdmin.value && isPlatform.value)
const router = useRouter()
const route = useRoute()

const dayOptions = [7, 30, 90]
const COST_SOURCE_LABELS = {
  PRODUCTION: '线上生产',
  GOLDEN_SET: 'Golden Set（黄金集）',
  MANUAL: '手动评测',
}
const metricOptions = [
  { key: 'overall', label: '总体', color: '#0f766e' },
  { key: 'faithfulness', label: '忠实度', color: 'var(--primary-hover)' },
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
// 筛选下拉的选项:刷新时 URL 的 kbId 会先于 listKb 到达,原生 select 找不到匹配 option
// 会短暂空白;这里为"尚未在下拉数据里的选中项"补一个占位项,消除空白。
const kbFilterOptions = computed(() => {
  const opts = kbOptions.value || []
  if (kbId.value != null && !opts.some((k) => k.id === kbId.value)) {
    return [{ id: kbId.value, name: kbName(kbId.value) }, ...opts]
  }
  return opts
})
const globalRatePercent = ref(1)
const globalSamplingEnabled = ref(true)
const goldenEnabledCount = ref(0)
// 全局配置与 KB 覆盖各用独立的保存态,避免点「保存覆盖」时「保存全局配置」按钮跟着进"保存中"。
const savingGlobal = ref(false)
const savingOverride = ref(false)
const replayingGolden = ref(false)
const settingsError = ref('')
// 平台评测预算（全平台共享）：真实值来自后端 /budget（配置预算 + 全平台本月已用），不前端写死。
const judgeBudget = ref({
  monthlyBudgetCny: 0,
  monthUsedCny: 0,
  exceeded: false,
  editable: false,
  platformShared: false,
})
const budgetExceeded = computed(() => !!judgeBudget.value?.exceeded)
const budgetPercent = computed(() => {
  const b = Number(judgeBudget.value?.monthlyBudgetCny) || 0
  const u = Number(judgeBudget.value?.monthUsedCny) || 0
  if (b <= 0) return 0
  return Math.min(100, Math.round((u / b) * 1000) / 10)
})
const budgetLevel = computed(() => {
  if (budgetExceeded.value) return 'critical'
  const p = budgetPercent.value
  return p >= 100 ? 'critical' : p >= 80 ? 'warn' : 'normal'
})
const editingBudget = ref(false)
const budgetInput = ref(0)
const savingBudget = ref(false)
function startEditBudget() {
  budgetInput.value = Number(judgeBudget.value?.monthlyBudgetCny) || 0
  editingBudget.value = true
}
async function saveBudget() {
  const amount = Number(budgetInput.value)
  if (!Number.isFinite(amount) || amount <= 0) {
    toast.error('配额必须大于 0')
    return
  }
  savingBudget.value = true
  try {
    const resp = await updateJudgeBudget(amount)
    const data = unwrapResponse(resp)
    if (data) judgeBudget.value = data
    editingBudget.value = false
    toast.success('配额已更新')
  } catch (error) {
    toast.error(resolveHttpError(error, { kind: 'budget' }))
  } finally {
    savingBudget.value = false
  }
}
const replayButtonTitle = computed(() => {
  if (!canManageOrg.value) return '仅组织所有者 / 管理员可触发回放'
  if (budgetExceeded.value) return '本月评测额度已用完，请联系平台管理员'
  return replayDisabledReason.value
})
const highCostConfirmed = ref(false)
const kbFilterError = ref('')
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

const SEVERITY_LABELS = {
  CRITICAL: '严重',
  WARN: '警告',
}

function translateAnomalyReason(reason, severity) {
  if (!reason || reason === 'stable') {
    return severity === 'CRITICAL'
      ? '触发严重异常阈值'
      : '失败率偏高，请检查 LLM 调用稳定性'
  }
  const dropMatch = /^overall_score_dropped_by_([\d.]+)%$/.exec(reason)
  if (dropMatch) {
    return `综合质量较上期下降 ${dropMatch[1]}%`
  }
  const faithfulnessMatch = /^faithfulness_dropped_by_([\d.]+)%$/.exec(reason)
  if (faithfulnessMatch) {
    return `答案忠实度较上期下降 ${faithfulnessMatch[1]}%`
  }
  const precisionMatch = /^context_precision_dropped_by_([\d.]+)%$/.exec(reason)
  if (precisionMatch) {
    return `上下文精度较上期下降 ${precisionMatch[1]}%`
  }
  const relevanceMatch = /^answer_relevance_dropped_by_([\d.]+)%$/.exec(reason)
  if (relevanceMatch) {
    return `答案相关性较上期下降 ${relevanceMatch[1]}%`
  }
  if (reason === 'sample_count_zero') return '近期评测样本数为 0'
  if (reason === 'cost_exceeds_budget') return '评测成本已超月度预算'
  return reason
}

const anomalyMessage = computed(() => {
  if (!anomaly.value) return null
  const severity = anomaly.value.severity
  if (!severity || severity === 'NORMAL') return null
  return {
    text: translateAnomalyReason(anomaly.value.reason, severity),
    level: SEVERITY_LABELS[severity] || severity,
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

const replayDisabledReason = computed(() => {
  if (replayingGolden.value) return '任务进行中'
  if (goldenEnabledCount.value <= 0) return '当前启用题数为 0，无法触发回放'
  return ''
})

const kbSamplingConfigs = computed(() =>
  samplingConfigs.value
    .filter((item) => item.scopeType === 'KB')
    .sort((a, b) => Number(a.scopeId || 0) - Number(b.scopeId || 0))
)

function unwrapResponse(resp) {
  const body = resp?.data !== undefined && resp?.data !== null ? resp.data : resp
  if (body != null && typeof body === 'object' && body.code === 200 && 'data' in body) {
    return body.data
  }
  if (body !== undefined && body !== null) {
    return body
  }
  return {}
}

function toCount(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

async function openSamplingDrawer() {
  showSamplingDrawer.value = true
  highCostConfirmed.value = false
  await loadSamplingSettings()
}

function closeSamplingDrawer() {
  showSamplingDrawer.value = false
  settingsError.value = ''
}

async function loadSamplingSettings() {
  settingsError.value = ''
  try {
    const [configsResp, kbResp, goldenResp, budgetResp] = await Promise.all([
      listSamplingConfigs(),
      listKb(),
      fetchGoldenSetEnabledCount(),
      fetchJudgeBudget(),
    ])
    samplingConfigs.value = unwrapResponse(configsResp) || []
    kbOptions.value = unwrapResponse(kbResp) || []
    goldenEnabledCount.value = toCount(unwrapResponse(goldenResp))
    const budget = unwrapResponse(budgetResp)
    if (budget) judgeBudget.value = budget
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
  await saveSampling(
    {
      scopeType: 'GLOBAL',
      sampleRate: percentToRate(globalRatePercent.value),
      enabled: globalSamplingEnabled.value,
    },
    'global',
  )
}

const showKbForm = ref(false)
const editingSamplingId = ref(null)
const togglingSamplingId = ref(null)

function openNewKbForm() {
  editingSamplingId.value = null
  kbOverrideForm.scopeId = null
  kbOverrideForm.ratePercent = 1
  kbOverrideForm.enabled = true
  settingsError.value = ''
  showKbForm.value = true
}

function editSampling(item) {
  editingSamplingId.value = item.id
  kbOverrideForm.scopeId = item.scopeId
  kbOverrideForm.ratePercent = ratePercent(item.sampleRate)
  kbOverrideForm.enabled = item.enabled !== false
  settingsError.value = ''
  showKbForm.value = true
}

function closeKbForm() {
  showKbForm.value = false
  editingSamplingId.value = null
  settingsError.value = ''
}

async function toggleSampling(item) {
  togglingSamplingId.value = item.id
  try {
    await saveSampling(
      { scopeType: 'KB', scopeId: item.scopeId, sampleRate: item.sampleRate, enabled: !item.enabled },
      'kb',
    )
  } finally {
    togglingSamplingId.value = null
  }
}

async function saveKbOverride() {
  if (!kbOverrideForm.scopeId) {
    settingsError.value = '请选择知识库'
    return
  }
  await saveSampling(
    {
      scopeType: 'KB',
      scopeId: kbOverrideForm.scopeId,
      sampleRate: percentToRate(kbOverrideForm.ratePercent),
      enabled: kbOverrideForm.enabled,
    },
    'kb',
  )
  if (!settingsError.value) closeKbForm()
}

// 平台基准回放（全平台视图）：跑全量黄金集，样本进平台健康视图。
async function replayPlatformBaseline() {
  const ok = await confirmDialog({
    title: '平台基准回放',
    message: '将对平台基准（固定 100 题）发起 LLM-as-Judge 评测，结果进「平台健康视图」，会产生判分调用与成本。确定继续？',
    confirmText: '开始回放',
    cancelText: '取消',
  })
  if (!ok) return
  replayingGolden.value = true
  settingsError.value = ''
  try {
    await replayGoldenSetNow({ limit: 100 })
    toast.success('平台基准回放已开始')
  } catch (error) {
    const msg = resolveHttpError(error, { kind: 'replay' })
    settingsError.value = msg
    toast.error(msg)
  } finally {
    setTimeout(() => {
      replayingGolden.value = false
    }, 1000)
  }
}

async function saveSampling(payload, which = 'global') {
  const rate = Number(payload.sampleRate)
  if (!Number.isFinite(rate) || rate < 0 || rate > 1) {
    settingsError.value = '抽样率必须在 0% 到 100% 之间'
    return
  }
  let confirmed = false
  if (rate > 0.1) {
    if (!highCostConfirmed.value) {
      toast.error('当前抽样率超过 10%，月度成本会显著增加，请勾选确认后再保存')
      return
    }
    confirmed = true
  }
  const savingFlag = which === 'kb' ? savingOverride : savingGlobal
  savingFlag.value = true
  settingsError.value = ''
  try {
    await upsertSamplingConfig(Object.assign({}, payload, { confirmed }))
    await loadSamplingSettings()
    toast.success('抽样配置已更新')
  } catch (error) {
    settingsError.value = resolveHttpError(error, { kind: 'sampling' })
    if (!error?.config?.silent) {
      toast.error(resolveHttpError(error, { kind: 'sampling' }))
    }
  } finally {
    savingFlag.value = false
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
  if (goldenEnabledCount.value <= 0) {
    toast.error('当前启用题数为 0，请先在评测实验室启用黄金集题目')
    return
  }
  // 回放会对本组织启用题目逐题发起 LLM-as-Judge 判分，产生调用与成本，触发前二次确认。
  const ok = await confirmDialog({
    title: '立即回放本组织黄金集',
    message: `将对本组织启用的 ${goldenEnabledCount.value} 道黄金题发起 LLM-as-Judge 评测（单次最多 50 题），会产生判分调用与相应成本，结果进本组织质量看板。确定继续？`,
    confirmText: '开始回放',
    cancelText: '取消',
  })
  if (!ok) return
  replayingGolden.value = true
  settingsError.value = ''
  try {
    await replayGoldenSetForOrg({ limit: 50 })
    await loadSamplingSettings()
    toast.success('回放已开始')
  } catch (error) {
    const msg = resolveHttpError(error, { kind: 'replay' })
    settingsError.value = msg
    toast.error(msg)
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

function kbDisplayName(id) {
  return `知识库 #${id}`
}

function kbName(id) {
  const kb = kbOptions.value.find((item) => item.id === id)
  return kb?.name || kbDisplayName(id)
}

function parseHttpError(error) {
  const status = error?.response?.status || error?.status
  if (status === 401) {
    clearSession()
    router.push('/login')
    return '登录已失效，请重新登录'
  }
  return resolveHttpError(error, { load: true, kind: 'overview' })
}

function formatScore(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) {
    return (sampleCount.value || 0) <= 0 ? '0.00' : '—'
  }
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
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
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

// 最差 case 的问题摘要:后端把 issues 首条(可能是机器码 bottleneck=X)直接给到 topIssue,
// 这里转成中文:NONE→无明显问题,其余→检索瓶颈/生成瓶颈/两者皆有;非 bottleneck 的摘要原样展示。
function formatTopIssue(topIssue) {
  if (!topIssue) return '暂无问题摘要'
  const m = /^bottleneck=(\w+)/i.exec(topIssue)
  if (m) {
    const key = m[1].toUpperCase()
    return key === 'NONE' ? '无明显问题' : bottleneckLabel(key)
  }
  return topIssue
}

function scoreClass(value) {
  if (value === null || value === undefined || value === '') return 'score-muted'
  const num = Number(value)
  if (!Number.isFinite(num)) return 'score-muted'
  if ((sampleCount.value || 0) <= 0) return 'score-muted'
  if (num <= 0) return 'score-muted'
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

// 知识库筛选改为按名称下拉选择(不再让用户输 ID)。
function onKbFilterChange(event) {
  const val = event?.target?.value
  if (!val) {
    clearKbFilter()
    return
  }
  const parsed = Number.parseInt(val, 10)
  if (!Number.isFinite(parsed) || parsed <= 0) {
    clearKbFilter()
    return
  }
  kbId.value = parsed
  kbIdInput.value = String(parsed)
  router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value, kbId: parsed } })
}

function clearKbFilter() {
  kbIdInput.value = ''
  kbId.value = null
  router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value } })
}

// 供筛选下拉使用的知识库清单(按名称展示),页面加载时拉取。
async function loadKbOptions() {
  try {
    const resp = await listKb()
    kbOptions.value = unwrapResponse(resp) || []
  } catch {
    /* 忽略:下拉为空时退化为"全部" */
  }
}

onMounted(loadKbOptions)

function openKb(targetKbId) {
  if (!targetKbId) return
  router.push({ path: '/evaluation/quality', query: { days: normalizedDays.value, kbId: targetKbId } })
}

function openCase(id) {
  if (!id) return
  sessionStorage.setItem('qualityDashboardQuery', JSON.stringify(route.query || {}))
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
    const status = error?.response?.status || error?.status
    if (kbId.value && status === 403) {
      const msg = resolveHttpError(error, { kind: 'kb-filter' })
      toast.error(msg)
      globalError.value = msg
    } else if (status >= 500) {
      toast.error(resolveHttpError(error, { load: true }))
      globalError.value = resolveHttpError(error, { load: true })
    } else {
      globalError.value = parseHttpError(error)
    }
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
    const status = error?.response?.status || error?.status
    if (kbId.value && status === 403) {
      const msg = resolveHttpError(error, { kind: 'kb-filter' })
      toast.error(msg)
      globalError.value = msg
    } else {
      globalError.value = parseHttpError(error)
    }
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
    const status = error?.response?.status || error?.status
    if (kbId.value && status === 403) {
      const msg = resolveHttpError(error, { kind: 'kb-filter' })
      toast.error(msg)
      globalError.value = msg
    } else {
      globalError.value = parseHttpError(error)
    }
    worstCases.value = []
  } finally {
    loading.worst = false
  }
}

async function loadCost() {
  loading.cost = true
  try {
    const response = await fetchCost(days.value, kbId.value)
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
    PRODUCTION: 'var(--primary)',
    GOLDEN_SET: '#06b6d4',
    MANUAL: '#8b5cf6',
  }
  const sourceOrder = ['PRODUCTION', 'GOLDEN_SET', 'MANUAL']
  const entries = sourceOrder.map((source) => ({
    key: source,
    label: COST_SOURCE_LABELS[source] || source,
    value: Number(cost.value?.costBySource?.[source] || 0),
    color: sourceMap[source],
  }))
  const total = entries.reduce((acc, item) => acc + item.value, 0)
  if (total <= 0) {
    return [{ key: 'EMPTY', label: '无调用', value: 0, pct: 100, color: '#e2e8f0' }]
  }
  return entries.map((item) => Object.assign({}, item, { pct: (item.value / total) * 100 }))
})

// OS-B1：组织切换是整页刷新且保留 URL，旧组织的 kbId 会残留到新组织（403 或口径错位）。
// 加载前校验 kbId 归属当前组织，不属于则清除筛选、按当前组织全量展示。
let orgKbIdSetPromise = null
async function kbBelongsToCurrentOrg(id) {
  if (!orgKbIdSetPromise) {
    orgKbIdSetPromise = listKb()
      .then((resp) => {
        const list = unwrapResponse(resp)
        return new Set((Array.isArray(list) ? list : []).map((kb) => kb.id))
      })
      .catch(() => null) // 校验请求失败不阻断加载，由后端范围校验兜底
  }
  const ids = await orgKbIdSetPromise
  return ids == null || ids.has(id)
}

watch(
  () => route.query,
  async (nextQuery) => {
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
        if (!(await kbBelongsToCurrentOrg(parsed))) {
          toast.info('已清除不属于当前组织的知识库筛选')
          router.replace({ path: '/evaluation/quality', query: { days: normalizedDays.value } })
          return // query 变化会重新触发本 watch 并全量加载
        }
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
  border-radius: 16px;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
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
  background: linear-gradient(180deg, var(--primary), var(--primary-hover));
  color: #fff;
  border: 1px solid var(--primary-hover);
  border-radius: 8px;
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 1px 2px rgba(37, 99, 235, 0.2);
}

.btn-settings:hover {
  background: linear-gradient(180deg, var(--primary-hover), #1d4ed8);
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
  border-color: var(--primary);
}
.kb-filter-select {
  min-width: 200px;
  max-width: 280px;
  border: 1px solid var(--border);
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff;
  color: var(--text);
  transition: border-color 0.15s ease;
}
.kb-filter-select:focus {
  outline: none;
  border-color: var(--primary);
}

.quality-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.kpi-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 16px 18px;
  box-shadow: var(--shadow-sm);
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
.clickable-row:hover { background: rgba(37, 99, 235,0.06); }

.kb-slice-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: separate;
  border-spacing: 0;
}

.kb-slice-table thead th,
.kb-slice-table tbody td {
  padding: 10px 12px;
  font-size: 13px;
  vertical-align: middle;
}

.kb-slice-table thead th {
  white-space: nowrap;
}

.kb-slice-table .col-name {
  width: auto;
  text-align: left;
}

.kb-slice-table .col-score {
  width: 64px;
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.kb-slice-table .col-trend {
  width: 88px;
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.kb-slice-table .col-count {
  width: 56px;
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.kb-slice-table tbody td.col-score,
.kb-slice-table tbody td.col-trend,
.kb-slice-table tbody td.col-count {
  color: #475569;
}

.kb-name-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #0f172a;
}

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
  height: 32px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
  background: #f1f5f9;
}

.cost-stack {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 500;
  color: #fff;
  min-width: 4px;
  transition: opacity 0.15s ease;
}

.cost-stack--narrow {
  min-width: 2px;
}

.cost-legend {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.legend-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-size: 13px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-label {
  color: #64748b;
  flex: 1;
  min-width: 0;
}

.legend-value {
  color: #0f172a;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
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

.link-button {
  border: none;
  background: transparent;
  color: var(--primary);
  cursor: pointer;
}

.drawer-close {
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.drawer-close:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.drawer-close:active {
  background: #e2e8f0;
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
  border: 1px solid var(--primary-hover);
  border-radius: 10px;
  background: linear-gradient(180deg, var(--primary), var(--primary-hover));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow:
    0 1px 2px rgba(37, 99, 235, 0.2),
    0 4px 14px rgba(37, 99, 235, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.18);
  transition: transform 0.15s ease, box-shadow 0.15s ease, filter 0.15s ease;
}

.btn-save-config:hover:not(:disabled) {
  filter: brightness(1.04);
  transform: translateY(-1px);
  box-shadow:
    0 2px 4px rgba(37, 99, 235, 0.22),
    0 8px 20px rgba(37, 99, 235, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.btn-save-config:active:not(:disabled) {
  transform: translateY(0);
  filter: brightness(0.98);
  box-shadow:
    0 1px 2px rgba(37, 99, 235, 0.2),
    inset 0 1px 2px rgba(0, 0, 0, 0.08);
}

.btn-save-config:disabled {
  opacity: 0.72;
  cursor: not-allowed;
  transform: none;
}

.golden-empty-hint {
  color: #b45309;
}

.sampling-cost-hint {
  margin: 10px 0 0;
  padding: 8px 11px;
  border-radius: 8px;
  background: #eafaf0;
  border: 1px solid #bfe6cf;
  color: #0e7a41;
  font-size: 12.5px;
  line-height: 1.6;
}

.budget-scope {
  margin-left: 8px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  background: #eef2f7;
  border-radius: 999px;
  padding: 2px 8px;
  vertical-align: middle;
}

.budget-bar {
  height: 10px;
  border-radius: 999px;
  background: #eef1f6;
  overflow: hidden;
  margin: 6px 0 6px;
}

.budget-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.35s ease;
}

.budget-fill--normal {
  background: linear-gradient(90deg, #2f6bff, #5a8bff);
}

.budget-fill--warn {
  background: linear-gradient(90deg, #d9930b, #f0b24a);
}

.budget-fill--critical {
  background: linear-gradient(90deg, #dc2626, #f26d6d);
}

.budget-meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 13px;
}

.budget-over {
  color: #dc2626;
  font-weight: 600;
}

.budget-over-hint {
  margin: 6px 0 0;
  padding: 8px 11px;
  border-radius: 8px;
  background: #fdecec;
  border: 1px solid #f4b8b8;
  color: #b42318;
  font-size: 12.5px;
  line-height: 1.6;
}

.budget-edit-btn {
  margin-left: 10px;
  font-size: 12.5px;
  font-weight: 600;
}

.budget-edit-form {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin: 6px 0 4px;
}

.budget-edit-form input {
  width: 96px;
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.link-button:disabled,
.add-sampling-btn:disabled,
.switch:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 线上抽样表格 */
.sampling-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin: 6px 0 4px;
}

.sampling-table th {
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-muted);
  padding: 0 8px 8px;
}

.sampling-table td {
  padding: 9px 8px;
  border-top: 1px solid var(--border);
  vertical-align: middle;
}

.sampling-table .st-name {
  font-weight: 600;
  color: #0f172a;
}

.sampling-table .st-rate {
  color: var(--primary);
  font-weight: 700;
}

.sampling-table .st-a {
  text-align: right;
  width: 48px;
}

.switch {
  width: 38px;
  height: 22px;
  border-radius: 999px;
  border: none;
  background: #cbd5e1;
  position: relative;
  cursor: pointer;
  padding: 0;
  transition: background 0.18s ease;
}

.switch--on {
  background: #16a34a;
}

.switch:disabled {
  opacity: 0.6;
  cursor: default;
}

.switch-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.25);
  transition: transform 0.18s ease;
}

.switch--on .switch-knob {
  transform: translateX(16px);
}

.add-sampling-btn {
  margin-top: 8px;
  padding: 7px 13px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  color: #0f172a;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}

.add-sampling-btn:hover {
  background: #f8fafc;
}

/* 平台基准卡片 */
.platform-baseline {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px;
  background: #fbfcfe;
}

.pb-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.pb-title {
  font-weight: 700;
  font-size: 14.5px;
  color: #0f172a;
}

.pb-badge {
  margin-left: auto;
  font-size: 11px;
  font-weight: 700;
  color: #3949ab;
  background: #eef2ff;
  border-radius: 999px;
  padding: 2px 9px;
}

.pb-count {
  margin: 4px 0;
}

.pb-count strong {
  font-size: 26px;
  font-weight: 800;
  color: #0f172a;
}

.pb-count span {
  color: var(--text-muted);
  font-size: 13px;
}

.global-readonly-hint {
  margin: 4px 0 10px;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: #64748b;
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.btn-save-config.is-saving {
  background: linear-gradient(180deg, #60a5fa 0%, var(--primary) 100%);
}

.btn-save-config--secondary {
  width: auto;
  align-self: flex-start;
  background: linear-gradient(180deg, var(--primary), var(--primary-hover));
  border-color: var(--primary-hover);
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
  border-color: var(--primary);
  color: var(--primary);
  background: #f0f7ff;
}
.btn-ghost-small.active {
  border-color: var(--primary);
  color: var(--primary);
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

  .cost-legend {
    grid-template-columns: 1fr;
  }
}
</style>
