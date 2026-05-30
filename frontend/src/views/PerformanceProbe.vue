<template>
  <div class="page-body">
    <div class="probe-shell">
      <section class="probe-controls">
        <div>
          <div class="panel-kicker">Manual Probe</div>
          <h2 class="panel-title">性能诊断</h2>
          <p class="panel-note">线上手动诊断工具，不轮询、不后台采集；仅在点击开始诊断时发起少量请求。</p>
        </div>

        <label class="field">
          <span>查询语句</span>
          <textarea v-model="form.query" rows="3" placeholder="例如：员工报销流程是什么" />
        </label>

        <div class="field-grid">
          <label class="field">
            <span>知识库</span>
            <select v-model="form.kbId">
              <option :value="null">全部知识库</option>
              <option v-for="kb in kbList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
            </select>
          </label>

          <label class="field">
            <span>诊断模式</span>
            <select v-model="form.mode">
              <option value="single">单策略</option>
              <option value="all">五路对比</option>
            </select>
          </label>
        </div>

        <div class="field-grid">
          <label class="field">
            <span>检索策略</span>
            <select v-model="form.strategy" :disabled="form.mode === 'all'">
              <option value="keyword">keyword</option>
              <option value="vector">vector</option>
              <option value="hybrid">hybrid</option>
              <option value="rewrite">rewrite</option>
              <option value="full">full</option>
            </select>
          </label>

          <label class="field">
            <span>重复次数</span>
            <select v-model.number="form.repeat">
              <option :value="1">1 次</option>
              <option :value="3">3 次</option>
              <option :value="5">5 次</option>
              <option :value="10">10 次</option>
            </select>
          </label>
        </div>

        <div class="field-grid">
          <label class="field">
            <span>Top K</span>
            <input v-model.number="form.topK" type="number" min="1" max="50">
          </label>

          <label class="field">
            <span>间隔 ms</span>
            <input v-model.number="form.intervalMs" type="number" min="0" max="5000" step="100">
          </label>
        </div>

        <div v-if="showVectorWeight || showRerankTopN" class="field-grid">
          <label v-if="showVectorWeight" class="field">
            <span>向量权重</span>
            <input v-model.number="form.vectorWeight" type="number" min="0" max="1" step="0.05">
          </label>

          <label v-if="showRerankTopN" class="field">
            <span>Rerank TopN</span>
            <input v-model.number="form.rerankTopN" type="number" min="1" max="50">
          </label>
        </div>
        <div class="param-hint">{{ advancedParamHint }}</div>
        <div class="run-estimate">
          本次将发起 <b>{{ plannedRequestCount }}</b> 个检索请求。线上建议先用 1-3 次、间隔不低于 1000ms。
        </div>

        <button class="run-btn" :disabled="running" @click="runProbe">
          {{ running ? '诊断中...' : '开始诊断' }}
        </button>

        <button v-if="rows.length" class="clear-btn" :disabled="running" @click="clearRows">
          清空结果
        </button>
      </section>

      <section class="probe-main">
        <div class="top-strip">
          <div>
            <h1>检索链路耗时快照</h1>
            <p>用于线上低流量排查单次检索链路：全链路、rewrite、vector、keyword、rerank 分段耗时会直接展示；embedding 和 PG 内部分段仍以服务端日志为准。</p>
          </div>
          <div class="status-pill" :class="{ active: running }">
            {{ running ? `运行中 ${progressText}` : '待命' }}
          </div>
        </div>

        <div class="metric-grid">
          <div class="metric-card">
            <span>样本数</span>
            <b>{{ rows.length }}</b>
          </div>
          <div class="metric-card">
            <span>平均耗时</span>
            <b>{{ summary.avg }}ms</b>
          </div>
          <div class="metric-card">
            <span>P95</span>
            <b>{{ summary.p95 }}ms</b>
          </div>
          <div class="metric-card">
            <span>失败数</span>
            <b :class="{ danger: summary.errors > 0 }">{{ summary.errors }}</b>
          </div>
        </div>

        <div v-if="lastRow" class="stage-panel">
          <div class="section-head">
            <h3>最近一次分段</h3>
            <span>{{ strategyLabel(lastRow.strategy) }} · {{ lastRow.resultCount }} 条结果</span>
          </div>
          <div class="stage-bars">
            <div v-for="stage in stageBreakdown" :key="stage.key" class="stage-row">
              <div class="stage-label">{{ stage.label }}</div>
              <div class="stage-track">
                <div class="stage-fill" :style="{ width: stage.width, background: stage.color }" />
              </div>
              <div class="stage-value">{{ stage.value == null ? '-' : `${stage.value}ms` }}</div>
            </div>
          </div>
        </div>

        <div class="table-panel">
          <div class="section-head">
            <h3>诊断记录</h3>
            <span>按执行时间倒序</span>
          </div>
          <div v-if="!rows.length" class="empty-state">点击开始诊断后，这里会展示每次请求的耗时。</div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>策略</th>
                  <th>总耗时</th>
                  <th>浏览器耗时</th>
                  <th>Rewrite</th>
                  <th>Vector</th>
                  <th>Keyword</th>
                  <th>Rerank</th>
                  <th>未拆分</th>
                  <th>结果</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in displayRows" :key="row.id">
                  <td>{{ row.id }}</td>
                  <td>{{ strategyLabel(row.strategy) }}</td>
                  <td class="strong">{{ row.latencyMs }}ms</td>
                  <td>{{ row.clientLatencyMs }}ms</td>
                  <td>{{ formatMs(row.rewriteLatencyMs) }}</td>
                  <td>{{ formatMs(row.vectorLatencyMs) }}</td>
                  <td>{{ formatMs(row.keywordLatencyMs) }}</td>
                  <td>{{ formatMs(row.rerankLatencyMs) }}</td>
                  <td>{{ formatMs(row.otherLatencyMs) }}</td>
                  <td>{{ row.resultCount }}</td>
                  <td>
                    <span class="row-status" :class="{ ok: row.ok, bad: !row.ok }">
                      {{ row.ok ? 'OK' : 'FAIL' }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { listKb } from '../api/kb'
import { search as searchApi } from '../api/search'

const kbList = ref([])
const rows = ref([])
const running = ref(false)
const completed = ref(0)
const planned = ref(0)
let nextId = 1

const form = reactive({
  query: '',
  kbId: null,
  mode: 'single',
  strategy: 'hybrid',
  repeat: 1,
  topK: 5,
  intervalMs: 1000,
  vectorWeight: 0.55,
  rerankTopN: 5,
})

const strategies = ['keyword', 'vector', 'hybrid', 'rewrite', 'full']

const displayRows = computed(() => [...rows.value].sort((a, b) => b.id - a.id))
const lastRow = computed(() => displayRows.value[0] ?? null)
const progressText = computed(() => `${completed.value}/${planned.value}`)
const plannedRequestCount = computed(() => {
  const strategyCount = form.mode === 'all' ? strategies.length : 1
  return strategyCount * clampNumber(form.repeat, 1, 10)
})
const showVectorWeight = computed(() =>
  form.mode === 'all' || form.strategy === 'hybrid' || form.strategy === 'full'
)
const showRerankTopN = computed(() =>
  form.mode === 'all' || form.strategy === 'full'
)
const advancedParamHint = computed(() => {
  if (form.mode === 'all') {
    return '五路对比时：向量权重仅作用于 hybrid/full，Rerank TopN 仅作用于 full。'
  }
  if (form.strategy === 'full') {
    return 'full 策略会使用向量权重和 Rerank TopN。'
  }
  if (form.strategy === 'hybrid') {
    return 'hybrid 策略只使用向量权重，不使用 Rerank TopN。'
  }
  return `${strategyLabel(form.strategy)} 策略不使用向量权重和 Rerank TopN。`
})

const summary = computed(() => {
  const okRows = rows.value.filter((r) => r.ok)
  const values = okRows.map((r) => r.latencyMs).sort((a, b) => a - b)
  if (!values.length) {
    return { avg: 0, p95: 0, errors: rows.value.length }
  }
  const avg = Math.round(values.reduce((sum, v) => sum + v, 0) / values.length)
  const p95Index = Math.min(values.length - 1, Math.ceil(values.length * 0.95) - 1)
  return {
    avg,
    p95: values[p95Index],
    errors: rows.value.filter((r) => !r.ok).length,
  }
})

const stageBreakdown = computed(() => {
  const row = lastRow.value
  if (!row) return []
  const max = Math.max(row.latencyMs, row.clientLatencyMs, 1)
  return [
    { key: 'total', label: '全链路', value: row.latencyMs, color: '#334155' },
    { key: 'rewrite', label: 'Rewrite', value: row.rewriteLatencyMs, color: '#dc2626' },
    { key: 'vector', label: 'Vector', value: row.vectorLatencyMs, color: '#2563eb' },
    { key: 'keyword', label: 'Keyword', value: row.keywordLatencyMs, color: '#059669' },
    { key: 'rerank', label: 'Rerank', value: row.rerankLatencyMs, color: '#7c3aed' },
    { key: 'other', label: '未拆分', value: row.otherLatencyMs, color: '#f59e0b' },
  ].map((item) => ({
    ...item,
    width: item.value == null ? '0%' : `${Math.min(100, Math.round((item.value / max) * 100))}%`,
  }))
})

function buildPayload(strategy) {
  const payload = {
    query: form.query.trim(),
    strategy,
    topK: clampNumber(form.topK, 1, 50),
  }
  if (form.kbId != null) {
    payload.kbIds = [form.kbId]
  }
  if (strategy === 'hybrid' || strategy === 'full') {
    payload.vectorWeight = clampNumber(form.vectorWeight, 0, 1)
  }
  if (strategy === 'full') {
    payload.rerankTopN = clampNumber(form.rerankTopN, 1, 50)
  }
  return payload
}

async function runProbe() {
  const query = form.query.trim()
  if (!query) {
    alert('请输入查询语句')
    return
  }

  const selected = form.mode === 'all' ? strategies : [form.strategy]
  const repeat = clampNumber(form.repeat, 1, 10)
  planned.value = selected.length * repeat
  completed.value = 0
  running.value = true

  try {
    for (let i = 0; i < repeat; i++) {
      for (const strategy of selected) {
        await runOnce(strategy)
        completed.value += 1
        if (form.intervalMs > 0 && completed.value < planned.value) {
          await sleep(clampNumber(form.intervalMs, 0, 5000))
        }
      }
    }
  } finally {
    running.value = false
  }
}

async function runOnce(strategy) {
  const start = performance.now()
  try {
    const response = await searchApi(buildPayload(strategy))
    const data = response.data ?? {}
    const clientLatencyMs = Math.round(performance.now() - start)
    const latencyMs = data.latencyMs ?? clientLatencyMs
    const rewriteLatencyMs = normalizeMs(data.rewriteLatencyMs)
    const vectorLatencyMs = normalizeMs(data.vectorLatencyMs)
    const keywordLatencyMs = normalizeMs(data.keywordLatencyMs)
    const rerankLatencyMs = normalizeMs(data.rerankLatencyMs)
    rows.value.push({
      id: nextId++,
      ok: true,
      strategy: data.strategy ?? strategy,
      latencyMs,
      clientLatencyMs,
      rewriteLatencyMs,
      vectorLatencyMs,
      keywordLatencyMs,
      rerankLatencyMs,
      otherLatencyMs: estimateOtherLatency(
        latencyMs,
        rewriteLatencyMs,
        vectorLatencyMs,
        keywordLatencyMs,
        rerankLatencyMs
      ),
      resultCount: (data.results ?? []).length,
    })
  } catch (error) {
    rows.value.push({
      id: nextId++,
      ok: false,
      strategy,
      latencyMs: Math.round(performance.now() - start),
      clientLatencyMs: Math.round(performance.now() - start),
      rewriteLatencyMs: null,
      vectorLatencyMs: null,
      keywordLatencyMs: null,
      rerankLatencyMs: null,
      otherLatencyMs: null,
      resultCount: 0,
      error,
    })
  }
}

function estimateOtherLatency(total, rewriteMs, vectorMs, keywordMs, rerankMs) {
  const retrievalMs = Math.max(vectorMs ?? 0, keywordMs ?? 0)
  const known = (rewriteMs ?? 0) + retrievalMs + (rerankMs ?? 0)
  return Math.max(0, total - known)
}

function clearRows() {
  rows.value = []
  completed.value = 0
  planned.value = 0
}

function normalizeMs(value) {
  return value == null ? null : Math.round(Number(value))
}

function formatMs(value) {
  return value == null ? '-' : `${value}ms`
}

function clampNumber(value, min, max) {
  const n = Number(value)
  if (Number.isNaN(n)) return min
  return Math.max(min, Math.min(max, n))
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function strategyLabel(strategy) {
  const labels = {
    keyword: 'Keyword',
    vector: 'Vector',
    hybrid: 'Hybrid',
    rewrite: 'Rewrite',
    full: 'Full',
  }
  return labels[strategy] ?? strategy
}

onMounted(async () => {
  try {
    const response = await listKb()
    kbList.value = response.data ?? []
  } catch {
    kbList.value = []
  }
})
</script>

<style scoped>
.probe-shell {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
}

.probe-controls,
.probe-main {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: #fff;
}

.probe-controls {
  padding: 18px;
  align-self: start;
}

.panel-kicker {
  color: var(--blue);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.panel-title {
  margin: 2px 0 6px;
  color: var(--slate);
  font-size: 20px;
  line-height: 1.2;
}

.panel-note {
  margin-bottom: 16px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.field {
  display: block;
  margin-bottom: 12px;
}

.field span {
  display: block;
  margin-bottom: 4px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
}

.field input,
.field select,
.field textarea {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--text);
  font-size: 12px;
  outline: none;
}

.field input,
.field select {
  height: 34px;
  padding: 0 9px;
}

.field textarea {
  min-height: 78px;
  padding: 8px 9px;
  resize: vertical;
}

.field input:focus,
.field select:focus,
.field textarea:focus {
  border-color: var(--blue);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.10);
}

.field-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.field-grid .field:only-child {
  grid-column: 1 / -1;
}

.param-hint {
  margin: -4px 0 12px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.run-estimate {
  margin: -2px 0 12px;
  border: 1px solid #bfdbfe;
  border-radius: var(--radius-sm);
  background: #eff6ff;
  color: #1e40af;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.5;
}

.run-estimate b {
  font-size: 13px;
}

.run-btn,
.clear-btn {
  width: 100%;
  height: 36px;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.run-btn {
  background: var(--blue);
  color: #fff;
}

.run-btn:disabled,
.clear-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.clear-btn {
  margin-top: 8px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-muted);
}

.probe-main {
  padding: 18px;
}

.top-strip {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.top-strip h1 {
  margin: 0 0 4px;
  color: var(--slate);
  font-size: 22px;
  line-height: 1.2;
}

.top-strip p {
  max-width: 720px;
  color: var(--text-muted);
  font-size: 12px;
}

.status-pill {
  flex-shrink: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: #f8fafc;
  color: var(--text-muted);
  padding: 4px 10px;
  font-size: 11px;
  font-weight: 700;
}

.status-pill.active {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: var(--blue);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.metric-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #f8fafc;
  padding: 12px;
}

.metric-card span {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
}

.metric-card b {
  display: block;
  margin-top: 4px;
  color: var(--slate);
  font-size: 22px;
  line-height: 1.1;
}

.metric-card b.danger {
  color: var(--red);
}

.stage-panel,
.table-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 14px;
  margin-top: 12px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  color: var(--slate);
  font-size: 14px;
}

.section-head span {
  color: var(--text-muted);
  font-size: 11px;
}

.stage-bars {
  display: grid;
  gap: 8px;
}

.stage-row {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr) 72px;
  align-items: center;
  gap: 10px;
}

.stage-label,
.stage-value {
  color: var(--text-muted);
  font-size: 11px;
}

.stage-value {
  text-align: right;
  font-weight: 700;
}

.stage-track {
  height: 8px;
  overflow: hidden;
  border-radius: var(--radius-full);
  background: #e2e8f0;
}

.stage-fill {
  height: 100%;
  border-radius: inherit;
  transition: width 0.2s ease;
}

.empty-state {
  padding: 44px 0;
  color: var(--text-muted);
  font-size: 13px;
  text-align: center;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

th,
td {
  border-bottom: 1px solid var(--border);
  padding: 8px 9px;
  white-space: nowrap;
  text-align: left;
}

th {
  color: var(--text-muted);
  background: #f8fafc;
  font-size: 11px;
  font-weight: 700;
}

.strong {
  color: var(--slate);
  font-weight: 800;
}

.row-status {
  display: inline-block;
  min-width: 42px;
  border-radius: var(--radius-full);
  padding: 1px 8px;
  font-size: 10px;
  font-weight: 800;
  text-align: center;
}

.row-status.ok {
  background: #dcfce7;
  color: #166534;
}

.row-status.bad {
  background: #fee2e2;
  color: #991b1b;
}

@media (max-width: 980px) {
  .probe-shell {
    grid-template-columns: 1fr;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .field-grid,
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .top-strip,
  .section-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
