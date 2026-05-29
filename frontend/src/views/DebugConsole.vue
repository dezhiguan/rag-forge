<template>
  <div>
    <div class="page-body">
      <div class="debug-layout">
        <div class="debug-left" :class="{ 'debug-params-collapsed': !mobileParamsOpen }">
          <div class="panel-title" @click="toggleParams">⚙️ 检索参数</div>
          <div class="param-row">
            <div class="param-label">知识库</div>
            <select v-model="config.kbId" class="param-select" @change="onKbChange">
              <option :value="null">全部知识库</option>
              <option v-for="kb in kbList" :key="kb.id" :value="kb.id">
                {{ kb.name }}
              </option>
            </select>
          </div>
          <div class="param-row">
            <div class="param-label">文档</div>
            <select v-model="config.docId" class="param-select" :disabled="!config.kbId">
              <option :value="null">全部文档</option>
              <option v-for="doc in docList" :key="doc.id" :value="doc.id">
                {{ doc.filename }}
              </option>
            </select>
          </div>
          <div class="param-row">
            <div class="param-label">检索策略</div>
            <select v-model="config.strategy" class="param-select">
              <option value="vector">vector（向量检索）</option>
              <option value="keyword">keyword（关键词检索）</option>
              <option value="rewrite">rewrite（Query 改写检索）</option>
              <option value="hybrid">hybrid（混合检索）</option>
              <option value="full">full（全链路 Reranker）</option>
            </select>
          </div>
          <div class="param-row">
            <div class="param-label">Top-K <span style="color:var(--text-muted);font-weight:400;font-size:10px">返回结果数</span></div>
            <div class="param-slider">
              <input type="range" v-model.number="config.topK" min="1" max="20">
              <span class="param-val">{{ config.topK }}</span>
            </div>
          </div>
          <template v-if="config.strategy === 'hybrid' || config.strategy === 'full'">
            <div class="param-row">
              <div class="param-label">向量权重</div>
              <div class="param-slider">
                <input type="range" v-model.number="config.vectorWeight" min="0" max="1" step="0.05">
                <span class="param-val">{{ config.vectorWeight.toFixed(2) }}</span>
              </div>
            </div>
            <div class="param-row">
              <div class="param-label">BM25 权重</div>
              <div class="param-slider">
                <input type="range" :value="1 - config.vectorWeight" min="0" max="1" step="0.05" @input="config.vectorWeight = +(1 - $event.target.value).toFixed(2)">
                <span class="param-val">{{ (1 - config.vectorWeight).toFixed(2) }}</span>
              </div>
            </div>
          </template>
          <div class="param-row">
            <div class="param-label">Rerank TopN <span style="color:var(--text-muted);font-weight:400;font-size:10px">重排序数量</span></div>
            <div class="param-slider">
              <input type="range" v-model.number="config.rerankTopN" min="1" max="10">
              <span class="param-val">{{ config.rerankTopN }}</span>
            </div>
          </div>
          <div class="divider"></div>
          <div class="panel-title">对比模式</div>
          <label class="radio-row" v-for="m in compareModes" :key="m">
            <input type="radio" v-model="config.compareMode" :value="m" name="mode"> {{ m }}
          </label>
          <div v-if="config.compareMode === 'A/B 对比'" class="param-row" style="margin-top:8px;">
            <div class="param-label">策略 B</div>
            <select v-model="config.compareStrategyB" class="param-select">
              <option v-for="s in availableStrategiesB" :key="s.value" :value="s.value">
                {{ s.label }}
              </option>
            </select>
          </div>
          <button class="search-btn" :disabled="searching" @click="doSearch">
            {{ searching ? '检索中…' : '🔍 检索' }}
          </button>
        </div>

        <div class="debug-center">
          <input
            class="search-input"
            v-model="query"
            placeholder="输入检索查询..."
            :disabled="searching"
            @keyup.enter="doSearch"
          >
          <div class="results-info">
            检索结果
            <span v-if="compareResults.length > 1 && searched" class="time-text">
              · {{ compareResults[activeCompareTab]?.label }}
            </span>
            <span v-else-if="searched" class="time-text">
              · {{ currentStrategyLabel }} · {{ formattedLatency }}
            </span>
          </div>
          <div v-if="rewrittenQueries.length" class="rewritten-queries">
            改写查询：{{ rewrittenQueries.join(' | ') }}
          </div>

          <!-- 对比模式摘要 -->
          <div v-if="compareResults.length > 1 && searched" class="compare-summary">
            <div class="compare-title">策略对比</div>
            <div class="compare-grid">
              <div
                v-for="cr in compareResults"
                :key="cr.label"
                class="compare-cell"
                :class="{ best: cr.label === bestCompareLabel }"
              >
                <div class="compare-label">{{ cr.label }}</div>
                <div class="compare-metric">{{ cr.latencyMs }}ms</div>
                <div class="compare-sub">耗时</div>
                <div class="compare-metric">{{ cr.resultCount }} 条</div>
                <div class="compare-sub">结果</div>
                <div class="compare-metric">{{ formatScore(cr.topScore) }}</div>
                <div class="compare-sub">最高分</div>
              </div>
            </div>
            <!-- 策略 Tab 切换 -->
            <div class="compare-tabs">
              <button
                v-for="(cr, idx) in compareResults"
                :key="cr.label"
                class="compare-tab"
                :class="{ active: activeCompareTab === idx }"
                @click="activeCompareTab = idx"
              >
                {{ cr.label }}
              </button>
            </div>
          </div>

          <div v-if="searching" class="state-hint">
            <div class="state-icon">⏳</div>
            <div class="state-title">检索中...</div>
          </div>
          <div v-else-if="searched && displayResults.length === 0" class="state-hint">
            <div class="state-icon">🔍</div>
            <div class="state-title">未找到匹配的文档块</div>
            <div class="state-desc">尝试调整检索策略或扩大 Top-K</div>
          </div>
          <template v-else>
            <div
              v-for="(r, i) in displayResults"
              :key="r.chunkId ?? i"
              class="result-card"
              :class="{ top: isTopResult(i, r), selected: selectedChunkIds.includes(r.chunkId) }"
            >
              <div class="result-head">
                <label v-if="showSaveModal" class="result-check">
                  <input
                    type="checkbox"
                    :value="r.chunkId"
                    v-model="selectedChunkIds"
                  >
                </label>
                <span class="result-doc">📄 {{ r.filename }} #{{ r.chunkIndex }}</span>
                <span
                  class="result-score"
                  :style="{ color: scoreColor(displayScore(r)) }"
                >
                  Score {{ formatScore(displayScore(r)) }}
                </span>
              </div>
              <div class="result-text" :title="r.content" v-html="highlightContent(r.content, query)"></div>
              <div class="result-meta">
                <template v-if="activeStrategyRef === 'hybrid'">
                  向量 {{ (r.vectorScore ?? 0).toFixed(4) }} | BM25 {{ (r.bm25Score ?? 0).toFixed(2) }} | 融合 {{ (r.finalScore ?? 0).toFixed(4) }}
                </template>
                <template v-else-if="activeStrategyRef === 'full'">
                  向量 {{ (r.vectorScore ?? 0).toFixed(4) }} | BM25 {{ (r.bm25Score ?? 0).toFixed(2) }} | Rerank {{ (r.finalScore ?? 0).toFixed(4) }}
                </template>
                <template v-else>
                  {{ activeStrategyRef === 'keyword' ? 'BM25' : '向量' }} {{ formatScore(displayScore(r)) }}
                </template>
              </div>
            </div>
          </template>

          <div v-if="displayResults.length" class="save-case" @click="openSaveModal">
            💾 保存为评测用例
          </div>
        </div>

        <div class="debug-right">
          <div class="panel-title">Prompt 预览</div>
          <div class="prompt-block" v-if="displayResults.length">
            <pre class="prompt-full">{{ fullPrompt }}</pre>
          </div>
          <div v-else class="prompt-block">
            <div class="prompt-line dim">执行检索后将展示完整 Prompt 输入</div>
          </div>
          <div class="prompt-cost">
            <div class="cost-row"><span>输入 Prompt</span><span>{{ promptTokens }} tk</span></div>
            <div class="cost-row"><span>预计输出 Completion</span><span>{{ estimatedCompletionTokens }} tk</span></div>
            <div class="cost-row total"><span>预估成本</span><span>¥{{ estimatedCost }}</span></div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="showSaveModal" class="modal-mask" @click.self="closeSaveModal">
      <div class="modal">
        <h3 class="modal-title">保存为评测用例</h3>
        <label class="field">
          <span>目标数据集 *</span>
          <select v-model="saveDatasetId" class="param-select modal-select">
            <option :value="null" disabled>请选择数据集</option>
            <option v-for="ds in evalDatasets" :key="ds.id" :value="ds.id">
              {{ ds.name }}（{{ ds.questionCount ?? 0 }} 题）
            </option>
          </select>
        </label>
        <div class="field">
          <span>期望命中的结果（勾选上方检索结果中的 chunk）</span>
          <div class="selected-summary">
            {{ selectedChunkIds.length ? `已选 ${selectedChunkIds.length} 个 chunk` : '未选择，将保存为空期望列表' }}
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn-ghost" @click="closeSaveModal">取消</button>
          <button class="btn-primary" :disabled="savingCase" @click="onSaveCase">
            {{ savingCase ? '保存中…' : '确认保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listKb } from '../api/kb'
import { listDocuments } from '../api/document'
import { search as searchApi } from '../api/search'
import { listEvalDatasets, saveQuestionFromSearch } from '../api/eval'

const route = useRoute()
const router = useRouter()
const docList = ref([])
const pendingDocIdFromRoute = ref(null)

const query = ref('')
const kbList = ref([])
const results = ref([])
const searching = ref(false)
const searched = ref(false)
const mobileParamsOpen = ref(true)
const isMobile = typeof window !== 'undefined' && window.innerWidth <= 768

function toggleParams() {
  if (typeof window === 'undefined' || window.innerWidth > 768) return
  mobileParamsOpen.value = !mobileParamsOpen.value
}
const latencyMs = ref(0)
const strategy = ref('')
const rewrittenQueries = ref([])
const vectorLatencyMs = ref(null)
const keywordLatencyMs = ref(null)
const rerankLatencyMs = ref(null)

const showSaveModal = ref(false)
const evalDatasets = ref([])
const saveDatasetId = ref(null)
const selectedChunkIds = ref([])
const savingCase = ref(false)

const config = reactive({
  kbId: null,
  docId: null,
  strategy: 'vector',
  topK: 8,
  vectorWeight: 0.55,
  rerankTopN: 5,
  compareMode: '单策略',
  compareStrategyB: 'hybrid',
})

const compareModes = ['单策略', 'A/B 对比', '四路对比']
const compareResults = ref([])
const activeCompareTab = ref(0)

const allStrategies = [
  { value: 'vector', label: 'vector（向量检索）' },
  { value: 'keyword', label: 'keyword（关键词检索）' },
  { value: 'rewrite', label: 'rewrite（Query改写）' },
  { value: 'hybrid', label: 'hybrid（混合检索）' },
  { value: 'full', label: 'full（全链路 Reranker）' },
]

const availableStrategiesB = computed(() =>
  allStrategies.filter(s => s.value !== config.strategy)
)

const currentStrategyLabel = computed(() => {
  const s = strategy.value || config.strategy
  return strategyLabel(s)
})

const bestCompareLabel = computed(() => {
  if (compareResults.value.length <= 1) return null
  const sorted = [...compareResults.value].sort((a, b) => b.topScore - a.topScore)
  return sorted[0]?.label ?? null
})

const displayResults = computed(() => {
  if (compareResults.value.length > 1) {
    return compareResults.value[activeCompareTab.value]?.results ?? []
  }
  return results.value
})

const promptTokens = computed(() => {
  const source = compareResults.value.length > 1
    ? (compareResults.value[activeCompareTab.value]?.results ?? [])
    : results.value
  if (!source.length) return 0
  return source.slice(0, config.rerankTopN).reduce((s, r) => s + Math.floor((r.content?.length || 0) / 2), 0) + 180
})

const fullPrompt = computed(() => {
  const source = compareResults.value.length > 1
    ? (compareResults.value[activeCompareTab.value]?.results ?? [])
    : results.value
  if (!source.length) return ''

  const chunks = source.slice(0, config.rerankTopN)
  const contextParts = chunks.map((r, i) =>
    `[${i + 1}] ${r.filename} #${r.chunkIndex}:\n${r.content}`
  )
  const context = contextParts.join('\n\n')

  return `系统：你是RAG知识引擎，只基于以下Context回答问题。

Context：
${context}

用户：${query.value}`
})

const estimatedCompletionTokens = computed(() => {
  if (promptTokens.value === 0) return 0
  return Math.max(80, Math.floor(promptTokens.value * 0.25))
})

const estimatedCost = computed(() => {
  // DeepSeek-V3 定价：输入 ¥1/M tk，输出 ¥2/M tk
  const inputCost = promptTokens.value * 0.000001
  const outputCost = estimatedCompletionTokens.value * 0.000002
  return (inputCost + outputCost).toFixed(4)
})

const formattedLatency = computed(() => {
  const active = strategy.value || config.strategy
  if (active === 'hybrid') {
    const parts = []
    if (keywordLatencyMs.value != null) parts.push(`BM25 ${keywordLatencyMs.value}ms`)
    if (vectorLatencyMs.value != null) parts.push(`Vector ${vectorLatencyMs.value}ms`)
    return parts.length ? `耗时 ${parts.join(' + ')}` : `耗时 ${latencyMs.value}ms`
  }
  if (active === 'full') {
    const parts = []
    if (keywordLatencyMs.value != null) parts.push(`BM25 ${keywordLatencyMs.value}ms`)
    if (vectorLatencyMs.value != null) parts.push(`Vector ${vectorLatencyMs.value}ms`)
    if (rerankLatencyMs.value != null) parts.push(`Rerank ${rerankLatencyMs.value}ms`)
    return parts.length ? `耗时 ${parts.join(' + ')}` : `耗时 ${latencyMs.value}ms`
  }
  if (active === 'keyword') {
    const cost = keywordLatencyMs.value ?? latencyMs.value
    return `耗时 BM25 ${cost}ms`
  }
  const cost = vectorLatencyMs.value ?? latencyMs.value
  return `耗时 Vector ${cost}ms`
})

function excerpt(text, maxLen = 200) {
  if (!text) return ''
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLen) return normalized
  return `${normalized.slice(0, maxLen)}…`
}

function escapeHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function highlightContent(content, q) {
  const text = excerpt(content, 200)
  let html = escapeHtml(text)
  const keyword = q?.trim()
  if (!keyword) return html
  const pattern = new RegExp(keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
  html = html.replace(pattern, (match) => `<mark class="hl">${match}</mark>`)
  return html
}

const activeStrategyRef = computed(() => {
  if (compareResults.value.length > 1) {
    return compareResults.value[activeCompareTab.value]?.strategy ?? strategy.value
  }
  return strategy.value
})

function activeStrategy() {
  return activeStrategyRef.value
}

function displayScore(r) {
  const s = activeStrategy()
  if (s === 'hybrid' || s === 'full') return r.finalScore ?? 0
  return s === 'keyword' ? (r.bm25Score ?? 0) : (r.vectorScore ?? 0)
}

function formatScore(score) {
  const s = activeStrategy()
  if (s === 'hybrid' || s === 'full') return score.toFixed(4)
  return s === 'keyword' ? score.toFixed(2) : score.toFixed(4)
}

function isTopResult(index, r) {
  if (index !== 0) return false
  const s = activeStrategy()
  if (s === 'hybrid') {
    return (r.finalScore ?? 0) >= 0.06
  }
  if (s === 'full') {
    return (r.finalScore ?? 0) >= 0.8
  }
  if (s === 'keyword') {
    return (r.bm25Score ?? 0) >= 3.0
  }
  return (r.vectorScore ?? 0) >= 0.8
}

function scoreColor(score) {
  const s = activeStrategy()
  if (s === 'hybrid') {
    if (score >= 0.12) return '#10b981'
    if (score >= 0.06) return '#f59e0b'
    return '#64748b'
  }
  if (s === 'full') {
    if (score >= 0.9) return '#10b981'
    if (score >= 0.8) return '#f59e0b'
    return '#64748b'
  }
  if (s === 'keyword') {
    if (score >= 5) return '#10b981'
    if (score >= 3) return '#f59e0b'
    return '#64748b'
  }
  if (score >= 0.9) return '#10b981'
  if (score >= 0.8) return '#f59e0b'
  return '#64748b'
}

function sortResults(list, s) {
  const active = s ?? strategy.value
  const key =
    active === 'hybrid' || active === 'full'
      ? 'finalScore'
      : active === 'keyword'
        ? 'bm25Score'
        : 'vectorScore'
  return list.slice().sort((a, b) => (b[key] ?? 0) - (a[key] ?? 0))
}

async function loadDocsForKb(kbId) {
  if (!kbId) {
    docList.value = []
    config.docId = null
    return
  }
  try {
    const res = await listDocuments(kbId, 1, 200)
    docList.value = res.data?.list ?? []
  } catch {
    docList.value = []
  }
}

function onKbChange() {
  loadDocsForKb(config.kbId)
}

function buildPayload(q, strategy) {
  const payload = {
    query: q,
    topK: config.topK,
    strategy,
  }
  if (config.kbId != null) {
    payload.kbIds = [config.kbId]
  }
  if (config.docId != null) {
    payload.docIds = [config.docId]
  }
  if (strategy === 'hybrid' || strategy === 'full') {
    payload.vectorWeight = config.vectorWeight
  }
  if (strategy === 'full') {
    payload.rerankTopN = config.rerankTopN
  }
  return payload
}

async function doSearch() {
  const q = query.value.trim()
  if (!q) {
    alert('请输入检索查询')
    return
  }

  searching.value = true
  searched.value = false
  compareResults.value = []
  activeCompareTab.value = 0

  const strategies = resolveStrategies()
  try {
    const searches = strategies.map(async (s) => {
      const payload = buildPayload(q, s)
      const start = performance.now()
      try {
        const res = await searchApi(payload)
        const data = res.data ?? {}
        return {
          label: strategyLabel(s),
          strategy: data.strategy ?? s,
          results: sortResults(data.results ?? [], s),
          latencyMs: data.latencyMs ?? Math.round(performance.now() - start),
          vectorLatencyMs: data.vectorLatencyMs ?? null,
          keywordLatencyMs: data.keywordLatencyMs ?? null,
          rerankLatencyMs: data.rerankLatencyMs ?? null,
          rewrittenQueries: data.rewrittenQueries ?? [],
          topScore: topScore(data.results ?? [], s),
          resultCount: (data.results ?? []).length,
        }
      } catch {
        return {
          label: strategyLabel(s),
          strategy: s,
          results: [],
          latencyMs: Math.round(performance.now() - start),
          vectorLatencyMs: null,
          keywordLatencyMs: null,
          rerankLatencyMs: null,
          rewrittenQueries: [],
          topScore: 0,
          resultCount: 0,
        }
      }
    })

    const all = await Promise.all(searches)

    if (strategies.length === 1) {
      const first = all[0]
      results.value = first.results
      strategy.value = first.strategy
      rewrittenQueries.value = first.rewrittenQueries
      vectorLatencyMs.value = first.vectorLatencyMs
      keywordLatencyMs.value = first.keywordLatencyMs
      rerankLatencyMs.value = first.rerankLatencyMs
      latencyMs.value = first.latencyMs
      compareResults.value = []
    } else {
      compareResults.value = all
      // 同步单策略视图到第一个
      const first = all[0]
      results.value = first.results
      strategy.value = first.strategy
      rewrittenQueries.value = first.rewrittenQueries
      vectorLatencyMs.value = first.vectorLatencyMs
      keywordLatencyMs.value = first.keywordLatencyMs
      rerankLatencyMs.value = first.rerankLatencyMs
      latencyMs.value = first.latencyMs
    }
    searched.value = true
  } catch {
    results.value = []
    rewrittenQueries.value = []
    compareResults.value = []
    searched.value = true
  } finally {
    searching.value = false
  }
}

function resolveStrategies() {
  if (config.compareMode === 'A/B 对比') {
    return [config.strategy, config.compareStrategyB]
  }
  if (config.compareMode === '四路对比') {
    return ['vector', 'keyword', 'hybrid', 'full']
  }
  return [config.strategy]
}

function strategyLabel(s) {
  const map = { vector: '向量检索', keyword: '关键词检索', hybrid: '混合检索', full: '全链路Reranker' }
  return map[s] ?? s
}

function topScore(list, s) {
  if (!list || list.length === 0) return 0
  const key = (s === 'hybrid' || s === 'full') ? 'finalScore' : (s === 'keyword' ? 'bm25Score' : 'vectorScore')
  return Math.max(...list.map(r => r[key] ?? 0))
}

async function openSaveModal() {
  if (!query.value.trim()) {
    alert('请先输入检索查询')
    return
  }
  try {
    const res = await listEvalDatasets()
    evalDatasets.value = res.data ?? []
  } catch {
    evalDatasets.value = []
  }
  if (!evalDatasets.value.length) {
    alert('暂无评测数据集，请先在评测实验室创建')
    return
  }
  saveDatasetId.value = evalDatasets.value[0].id
  const source = compareResults.value.length > 1
    ? (compareResults.value[activeCompareTab.value]?.results ?? [])
    : results.value
  selectedChunkIds.value = source
    .slice(0, 3)
    .map((r) => r.chunkId)
    .filter((id) => id != null)
  showSaveModal.value = true
}

function closeSaveModal() {
  showSaveModal.value = false
  selectedChunkIds.value = []
}

async function onSaveCase() {
  if (!saveDatasetId.value) {
    alert('请选择数据集')
    return
  }
  savingCase.value = true
  try {
    await saveQuestionFromSearch(saveDatasetId.value, {
      question: query.value.trim(),
      strategy: strategy.value || config.strategy,
      selectedChunkIds: selectedChunkIds.value,
    })
    closeSaveModal()
    router.push({ path: '/eval', query: { datasetId: saveDatasetId.value, tab: 'datasets' } })
  } finally {
    savingCase.value = false
  }
}

onMounted(async () => {
  try {
    const res = await listKb()
    kbList.value = res.data ?? []
  } catch {
    kbList.value = []
  }
  // 从文档详情页跳转过来时，自动选中对应知识库和文档
  const kbIdParam = route.query.kbId
  const docIdParam = route.query.docId
  if (kbIdParam != null) {
    const id = Number(kbIdParam)
    if (!Number.isNaN(id) && kbList.value.some(kb => kb.id === id)) {
      config.kbId = id
      await loadDocsForKb(id)
      // 加载完文档列表后再选中指定文档
      if (docIdParam != null) {
        const docId = Number(docIdParam)
        if (!Number.isNaN(docId) && docList.value.some(d => d.id === docId)) {
          config.docId = docId
        }
      }
    }
  }
})
</script>

<style scoped>
.debug-layout { display: grid; grid-template-columns: 220px 1fr 240px; background: #fff; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; min-height: 420px; }
.debug-left { background: #f8fafc; border-right: 1px solid var(--border); padding: 16px; font-size: 11px; }
.debug-center { padding: 16px; font-size: 11px; }
.debug-right { background: #f8fafc; border-left: 1px solid var(--border); padding: 16px; font-size: 10px; font-family: 'SF Mono', Monaco, monospace; }
.panel-title { font-weight: 700; font-size: 12px; margin-bottom: 10px; color: var(--slate); }
.debug-right .panel-title { font-family: -apple-system, sans-serif; }
.param-row { margin-bottom: 10px; }
.param-label { font-size: 10px; color: var(--text-muted); margin-bottom: 3px; }
.param-select { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: var(--radius-sm); font-size: 10px; background: #fff; color: var(--text); outline: none; }
.param-slider { display: flex; align-items: center; gap: 8px; }
.param-slider input[type="range"] { flex: 1; height: 4px; -webkit-appearance: none; background: var(--border); border-radius: 2px; outline: none; }
.param-slider input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 14px; height: 14px; background: var(--blue); border-radius: 50%; cursor: pointer; }
.param-val { font-size: 11px; font-weight: 600; color: var(--text); min-width: 28px; text-align: right; }
.divider { border-top: 1px solid var(--border); margin: 12px 0; }
.radio-row { display: flex; align-items: center; gap: 6px; padding: 2px 0; cursor: pointer; font-size: 11px; }
.search-btn { width: 100%; margin-top: 12px; padding: 7px 0; background: var(--blue); color: #fff; border: none; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600; cursor: pointer; transition: background 0.15s; }
.search-btn:hover { background: #2563eb; }
.search-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.search-input { width: 100%; padding: 10px 12px; border: 1px solid var(--border); border-radius: var(--radius-sm); font-size: 12px; margin-bottom: 10px; outline: none; }
.search-input:focus { border-color: var(--blue); }
.results-info { font-weight: 600; margin-bottom: 10px; font-size: 11px; }
.time-text { color: var(--text-muted); font-weight: 400; }
.rewritten-queries {
  margin: -2px 0 10px;
  color: var(--text-muted);
  font-size: 11px;
}
.result-card { background: var(--light); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px; margin-bottom: 8px; }
.result-card.top { background: #f0fdf4; border-color: #bbf7d0; }
.result-card.selected { border-color: #93c5fd; background: #eff6ff; }
.result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; gap: 8px; }
.result-check { display: flex; align-items: center; flex-shrink: 0; }
.result-check input { cursor: pointer; }
.result-doc { font-weight: 600; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-score { font-weight: 700; font-size: 13px; flex-shrink: 0; }
.result-text { color: var(--gray); line-height: 1.5; margin-bottom: 4px; word-break: break-word; }
.result-text :deep(mark.hl) { background: #fef08a; color: inherit; padding: 0 2px; border-radius: 2px; }
.result-meta { color: var(--text-muted); font-size: 9px; }
.save-case { margin-top: 12px; padding: 8px; border: 1px dashed var(--blue); border-radius: var(--radius-sm); text-align: center; color: var(--blue); font-size: 11px; cursor: pointer; transition: background 0.15s; }
.save-case:hover { background: #eff6ff; }
.compare-summary { margin-bottom: 12px; }
.compare-title { font-weight: 600; font-size: 11px; margin-bottom: 8px; }
.compare-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(80px, 1fr)); gap: 6px; margin-bottom: 8px; }
.compare-cell { background: var(--light); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 8px; text-align: center; }
.compare-cell.best { background: #f0fdf4; border-color: #bbf7d0; }
.compare-label { font-weight: 700; font-size: 10px; color: var(--slate); margin-bottom: 4px; }
.compare-metric { font-weight: 700; font-size: 13px; color: var(--blue); }
.compare-cell.best .compare-metric { color: #166534; }
.compare-sub { font-size: 9px; color: var(--text-muted); }
.compare-tabs { display: flex; gap: 4px; margin-bottom: 8px; flex-wrap: wrap; }
.compare-tab { padding: 4px 10px; border: 1px solid var(--border); border-radius: 14px; background: #fff; font-size: 10px; cursor: pointer; color: var(--text-muted); transition: all 0.15s; }
.compare-tab.active { background: var(--blue); color: #fff; border-color: var(--blue); }
.compare-tab:hover:not(.active) { border-color: var(--blue); color: var(--blue); }
.prompt-block { margin-bottom: 12px; }
.prompt-full {
  margin: 0;
  padding: 8px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--gray);
  line-height: 1.7;
  word-break: break-word;
  white-space: pre-wrap;
  font-size: 9px;
  max-height: 360px;
  overflow-y: auto;
}
.prompt-line.dim { color: var(--text-muted); font-size: 9px; }
.prompt-cost { border-top: 1px solid var(--border); padding-top: 10px; font-family: -apple-system, sans-serif; }
.cost-row { display: flex; justify-content: space-between; padding: 2px 0; font-size: 10px; color: var(--text-muted); }
.cost-row.total { font-weight: 600; color: var(--cyan); }
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal {
  background: #fff;
  border-radius: var(--radius-md);
  padding: 24px;
  width: min(420px, 92vw);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.15);
  font-family: -apple-system, sans-serif;
}
.modal-title { font-size: 16px; margin-bottom: 16px; color: var(--slate); }
.field { display: block; margin-bottom: 14px; }
.field span { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
.modal-select { font-size: 12px; padding: 8px 10px; }
.selected-summary { font-size: 12px; color: var(--gray); }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
.btn-primary {
  background: var(--blue);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-ghost {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .debug-layout {
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .debug-left {
    border-right: none;
    border-bottom: 1px solid var(--border);
    padding: 10px 12px;
  }

  .debug-left .panel-title {
    cursor: pointer;
    user-select: none;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .debug-left .panel-title::after {
    content: '▼';
    font-size: 10px;
    margin-left: 4px;
  }

  .debug-params-collapsed .param-row,
  .debug-params-collapsed .divider,
  .debug-params-collapsed .radio-row,
  .debug-params-collapsed .search-btn {
    display: none;
  }

  .debug-center {
    padding: 10px 12px;
    min-height: 200px;
  }

  .debug-right {
    border-left: none;
    border-top: 1px solid var(--border);
    padding: 10px 12px;
  }

  .compare-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .search-input {
    font-size: 16px; /* 防止 iOS 缩放 */
  }
}
</style>
