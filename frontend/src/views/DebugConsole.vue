<template>
  <div>
    <div class="page-body">
      <Teleport to="#topbar-left">
        <span class="topbar-divider" />
        <span class="topbar-subtitle">向量 + 关键词双路召回、RRF 融合与精排的检索链路调试</span>
      </Teleport>
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
            <div class="param-label">检索模态</div>
            <select v-model="config.modality" class="param-select">
              <option value="text">text（文本）</option>
              <option value="image">image（图问图）</option>
              <option value="both">both（文本 + 图像）</option>
            </select>
          </div>
          <div v-if="config.modality === 'image' || config.modality === 'both'" class="param-row">
            <div class="param-label">Query 图片</div>
            <div class="query-image-upload">
              <label class="query-image-picker">
                <input
                  ref="queryImageInput"
                  class="query-image-input"
                  type="file"
                  accept="image/*"
                  @change="onQueryImageChange"
                >
                <div class="query-image-trigger">
                  <img
                    v-if="queryImageBase64"
                    :src="queryImageBase64"
                    alt="Query 预览"
                    class="query-image-preview"
                  >
                  <div v-else class="query-image-placeholder">
                    <span class="query-image-icon">+</span>
                    <span>选择图片</span>
                  </div>
                </div>
              </label>
              <div v-if="queryImageName" class="query-image-meta">
                <span class="query-image-name">{{ queryImageName }}</span>
                <button type="button" class="query-image-clear" title="清除" @click="clearQueryImage">×</button>
              </div>
            </div>
          </div>
          <div class="param-row">
            <div class="param-label">Top-K <span style="color:var(--text-muted);font-weight:400;font-size:10px">返回结果数</span></div>
            <div class="param-slider">
              <input type="range" v-model.number="config.topK" min="1" max="20">
              <span class="param-val">{{ config.topK }}</span>
            </div>
          </div>
          <template v-if="showVectorWeight">
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
          <div v-if="showRerankTopN" class="param-row">
            <div class="param-label">Rerank TopN <span style="color:var(--text-muted);font-weight:400;font-size:10px">重排序数量</span></div>
            <div class="param-slider">
              <input type="range" v-model.number="config.rerankTopN" min="1" max="10">
              <span class="param-val">{{ config.rerankTopN }}</span>
            </div>
          </div>
          <div class="param-hint">{{ strategyParamHint }}</div>
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
          <div v-if="currentRewrittenQueries.length" class="rewritten-queries">
            改写查询：{{ currentRewrittenQueries.join(' | ') }}
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
                <span v-if="r.chunkModality" class="result-modality">{{ r.chunkModality }}</span>
                <span
                  class="result-score"
                  :style="{ color: scoreColor(displayScore(r)) }"
                >
                  Score {{ formatScore(displayScore(r)) }}
                </span>
              </div>
              <div class="result-text" :title="r.content" v-html="highlightContent(r.content, query)"></div>
              <img
                v-if="r.imageUrl"
                :src="r.imageUrl"
                class="result-thumb"
                alt="chunk 图片预览"
                title="点击查看大图"
                @click="previewImage = r.imageUrl"
              >
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
          <div class="right-tab-bar">
            <button
              class="right-tab"
              :class="{ active: rightTab === 'prompt' }"
              @click="rightTab = 'prompt'"
            >
              Prompt 预览
            </button>
            <button
              class="right-tab"
              :class="{ active: rightTab === 'llm' }"
              @click="rightTab = 'llm'"
            >
              LLM 输出
            </button>
          </div>

          <template v-if="rightTab === 'prompt'">
            <div v-if="compareResults.length > 1" class="right-strategy-label">
              {{ strategyLabel(activeCompareStrategy) }}
            </div>
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
          </template>

          <template v-else>
            <div v-if="compareResults.length > 1" class="right-strategy-label">
              {{ strategyLabel(activeCompareStrategy) }}
            </div>
            <template v-if="displayResults.length">
              <button
                class="llm-btn"
                :disabled="llmLoading"
                @click="doLlmGenerate"
              >
                {{ llmLoading ? '⏳ 生成中…' : '🤖 模拟 LLM 输出' }}
              </button>
            </template>
            <div v-else class="prompt-block">
              <div class="prompt-line dim">执行检索后，切换到此 Tab 调用 LLM 生成回答</div>
            </div>

            <div v-if="llmError" class="llm-error">{{ llmError }}</div>

            <div v-if="llmOutput" class="llm-output-section">
              <div class="llm-output-text">{{ llmOutput }}</div>
              <div class="llm-meta">
                <span v-if="llmModel">模型 {{ llmModel }}</span>
                <span>⏱ {{ llmTotalMs }}ms</span>
                <span>📥 {{ llmPromptTokens }}tk</span>
                <span>📤 {{ llmCompletionTokens }}tk</span>
                <span style="font-weight:600;color:var(--cyan);">¥{{ llmCost }}</span>
              </div>
            </div>
          </template>
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
          <button class="btn btn-secondary" @click="closeSaveModal">取消</button>
          <button class="btn btn-primary" :disabled="savingCase" @click="onSaveCase">
            {{ savingCase ? '保存中…' : '确认保存' }}
          </button>
        </div>
      </div>
    </div>
    <ImageLightbox :src="previewImage" @close="previewImage = ''" />
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listKb } from '../api/kb'
import { listDocuments } from '../api/document'
import { search as searchApi } from '../api/search'
import { listEvalDatasets, saveQuestionFromSearch } from '../api/eval'
import { llmGenerate } from '../api/llm'
import { useToast } from '../composables/useToast'
import ImageLightbox from '../components/ImageLightbox.vue'

const toast = useToast()
const previewImage = ref('')

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

const rightTab = ref('prompt')
const llmLoading = ref(false)
const llmOutput = ref('')
const llmError = ref('')
const llmModel = ref('')
const llmTotalMs = ref(0)
const llmPromptTokens = ref(0)
const llmCompletionTokens = ref(0)

const config = reactive({
  kbId: null,
  docId: null,
  strategy: 'vector',
  topK: 5,
  vectorWeight: 0.55,
  rerankTopN: 5,
  compareMode: '单策略',
  compareStrategyB: 'hybrid',
  modality: 'text',
})
const queryImageBase64 = ref('')
const queryImageName = ref('')
const queryImageInput = ref(null)

const compareModes = ['单策略', 'A/B 对比', '五路对比']
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

const comparedStrategyValues = computed(() => {
  if (config.compareMode === '五路对比') {
    return allStrategies.map(s => s.value)
  }
  if (config.compareMode === 'A/B 对比') {
    return [config.strategy, config.compareStrategyB]
  }
  return [config.strategy]
})

const showVectorWeight = computed(() =>
  comparedStrategyValues.value.some(s => s === 'hybrid' || s === 'full')
)

const showRerankTopN = computed(() =>
  comparedStrategyValues.value.includes('full')
)

const strategyParamHint = computed(() => {
  if (config.compareMode === '五路对比') {
    return '五路对比时：向量/BM25 权重只作用于 hybrid/full，Rerank TopN 只作用于 full。'
  }
  if (config.compareMode === 'A/B 对比') {
    const names = comparedStrategyValues.value.map(strategyLabel).join(' + ')
    if (showVectorWeight.value && showRerankTopN.value) {
      return `${names}：当前只对 hybrid/full 使用权重，只对 full 使用 Rerank TopN。`
    }
    if (showVectorWeight.value) {
      return `${names}：当前只对 hybrid/full 使用权重。`
    }
    return `${names}：当前策略组合不使用权重和 Rerank TopN。`
  }
  if (config.strategy === 'full') {
    return 'full 策略会执行 Query 改写、混合召回和 Rerank，并使用权重与 Rerank TopN。'
  }
  if (config.strategy === 'hybrid') {
    return 'hybrid 策略会并行执行向量与关键词召回，只使用向量/BM25 权重。'
  }
  if (config.strategy === 'rewrite') {
    return 'rewrite 策略会先改写 Query，再执行多路向量检索，不使用权重和 Rerank TopN。'
  }
  return `${strategyLabel(config.strategy)} 策略不使用权重和 Rerank TopN。`
})

const currentStrategyLabel = computed(() => {
  const s = strategy.value || config.strategy
  return strategyLabel(s)
})

const bestCompareLabel = computed(() => {
  if (compareResults.value.length <= 1) return null
  const sorted = [...compareResults.value].sort((a, b) => b.topScore - a.topScore)
  return sorted[0]?.label ?? null
})

const activeCompareStrategy = computed(() => {
  if (compareResults.value.length > 1) {
    return compareResults.value[activeCompareTab.value]?.strategy ?? ''
  }
  return strategy.value
})

const displayResults = computed(() => {
  if (compareResults.value.length > 1) {
    return compareResults.value[activeCompareTab.value]?.results ?? []
  }
  return results.value
})

const currentRewrittenQueries = computed(() => {
  if (compareResults.value.length > 1) {
    return compareResults.value[activeCompareTab.value]?.rewrittenQueries ?? []
  }
  return rewrittenQueries.value
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

const llmCost = computed(() => {
  // qwen-plus 定价：输入 ¥0.8/M tk，输出 ¥2/M tk
  const inputCost = llmPromptTokens.value * 0.0000008
  const outputCost = llmCompletionTokens.value * 0.000002
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
  const id = Number(kbId)
  if (!Number.isInteger(id) || id <= 0) {
    docList.value = []
    config.docId = null
    return
  }
  try {
    const res = await listDocuments(id, 1, 200)
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
    modality: config.modality,
  }
  if ((config.modality === 'image' || config.modality === 'both') && queryImageBase64.value) {
    payload.queryImageBase64 = queryImageBase64.value
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
  if (!q && config.modality !== 'image') {
    toast.warning('请输入检索查询')
    return
  }
  if (config.modality === 'image' && !queryImageBase64.value) {
    toast.warning('请选择 Query 图片')
    return
  }

  searching.value = true
  searched.value = false
  compareResults.value = []
  activeCompareTab.value = 0

  const strategies = resolveStrategies()
  try {
    const searches = strategies.map(async (s) => {
      const payload = buildPayload(q || 'image-query', s)
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

function onQueryImageChange(event) {
  const file = event.target.files?.[0]
  queryImageBase64.value = ''
  queryImageName.value = ''
  if (!file) return
  queryImageName.value = `${file.name} · ${(file.size / 1024).toFixed(1)} KB`
  const reader = new FileReader()
  reader.onload = () => {
    queryImageBase64.value = String(reader.result || '')
  }
  reader.readAsDataURL(file)
}

function clearQueryImage() {
  queryImageBase64.value = ''
  queryImageName.value = ''
  if (queryImageInput.value) {
    queryImageInput.value.value = ''
  }
}

async function doLlmGenerate() {
  rightTab.value = 'llm'
  llmLoading.value = true
  llmError.value = ''
  llmOutput.value = ''
  try {
    const messages = [
      {
        role: 'system',
        content: '你是一个智能助手，根据提供的资料回答用户问题。如果资料中没有明确提到相关内容，用自然的语气告诉用户"从现有资料来看，没有找到相关的信息"，可以适当推测可能的原因，但不要编造不存在的事实。回答尽量口语化，不要用"根据提供的资料"这种生硬的表述。',
      },
      {
        role: 'user',
        content: fullPrompt.value,
      },
    ]
    const res = await llmGenerate(messages, 'qwen-plus', 0.3)
    const data = res.data
    llmOutput.value = data.content || '(空响应)'
    llmModel.value = data.model || ''
    llmTotalMs.value = data.totalMs || 0
    llmPromptTokens.value = data.promptTokens || 0
    llmCompletionTokens.value = data.completionTokens || 0
  } catch (e) {
    llmError.value = '调用失败: ' + (e?.response?.data?.msg || e?.message || '未知错误')
  } finally {
    llmLoading.value = false
  }
}

function resolveStrategies() {
  if (config.compareMode === 'A/B 对比') {
    return [config.strategy, config.compareStrategyB]
  }
  if (config.compareMode === '五路对比') {
    return ['vector', 'keyword', 'hybrid', 'full', 'rewrite']
  }
  return [config.strategy]
}

function strategyLabel(s) {
  const map = { vector: '向量检索', keyword: '关键词检索', hybrid: '混合检索', full: '全链路Reranker', rewrite: 'Query改写' }
  return map[s] ?? s
}

function topScore(list, s) {
  if (!list || list.length === 0) return 0
  const key = (s === 'hybrid' || s === 'full') ? 'finalScore' : (s === 'keyword' ? 'bm25Score' : 'vectorScore')
  return Math.max(...list.map(r => r[key] ?? 0))
}

async function openSaveModal() {
  if (!query.value.trim()) {
    toast.warning('请先输入检索查询')
    return
  }
  try {
    const res = await listEvalDatasets()
    evalDatasets.value = res.data ?? []
  } catch {
    evalDatasets.value = []
  }
  if (!evalDatasets.value.length) {
    toast.warning('暂无评测数据集，请先在评测实验室创建')
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
    toast.warning('请选择数据集')
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
.topbar-divider { width: 1px; height: 16px; background: var(--border); flex-shrink: 0; }
.topbar-subtitle {
  color: var(--text-muted);
  font-size: 12.5px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 768px) { .topbar-divider, .topbar-subtitle { display: none; } }
.page-body { padding: 20px 24px; }
.debug-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr) 260px; max-width: 100%; background: #fff; border: 1px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); overflow: hidden; min-height: calc(100vh - 88px); }
.debug-left { min-width: 0; background: #f8fafc; border-right: 1px solid var(--border); padding: 16px; font-size: 11px; }
.debug-center { min-width: 0; padding: 16px; font-size: 11px; }
.debug-right { min-width: 0; background: #f8fafc; border-left: 1px solid var(--border); padding: 16px; font-size: 10px; font-family: 'SF Mono', Monaco, monospace; }
.panel-title { font-weight: 700; font-size: 12px; margin-bottom: 10px; color: var(--slate); }
.debug-right .panel-title { font-family: -apple-system, sans-serif; }
.right-tab-bar {
  display: flex;
  gap: 0;
  margin-bottom: 12px;
  border-bottom: 2px solid var(--border);
}
.right-tab {
  flex: 1;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  padding: 6px 0;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  color: var(--text-muted);
  text-align: center;
  font-family: -apple-system, sans-serif;
  transition: all 0.15s;
}
.right-tab:hover { color: var(--slate); }
.right-tab.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}
.right-strategy-label {
  font-size: 10px;
  color: var(--primary);
  font-weight: 600;
  margin-bottom: 8px;
  font-family: -apple-system, sans-serif;
}
.param-row { margin-bottom: 10px; }
.param-label { font-size: 10px; color: var(--text-muted); margin-bottom: 3px; }
.param-select { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: var(--radius-sm); font-size: 10px; background: #fff; color: var(--text); outline: none; }
.param-slider { display: flex; align-items: center; gap: 8px; }
.param-slider input[type="range"] { flex: 1; height: 4px; -webkit-appearance: none; background: var(--border); border-radius: 2px; outline: none; }
.param-slider input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 14px; height: 14px; background: var(--primary); border-radius: 50%; cursor: pointer; }
.param-val { font-size: 11px; font-weight: 600; color: var(--text); min-width: 28px; text-align: right; }
.param-hint { margin: -2px 0 10px; color: var(--text-muted); font-size: 10px; line-height: 1.5; }
.query-image-upload { width: 100%; }
.query-image-input { display: none; }
.query-image-picker { display: block; cursor: pointer; }
.query-image-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 64px;
  padding: 8px;
  border: 1px dashed #cbd5e1;
  border-radius: var(--radius-sm);
  background: #f8fafc;
  transition: border-color 0.15s, background 0.15s;
}
.query-image-picker:hover .query-image-trigger {
  border-color: var(--primary);
  background: #eff6ff;
}
.query-image-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 500;
}
.query-image-icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  line-height: 1;
}
.query-image-preview {
  display: block;
  max-width: 100%;
  max-height: 72px;
  border-radius: 4px;
  object-fit: contain;
}
.query-image-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-top: 5px;
  font-size: 10px;
  color: var(--text-muted);
  line-height: 1.4;
}
.query-image-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.query-image-clear {
  flex-shrink: 0;
  border: none;
  background: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0 2px;
  font-size: 15px;
  line-height: 1;
}
.query-image-clear:hover { color: #ef4444; }
.divider { border-top: 1px solid var(--border); margin: 12px 0; }
.radio-row { display: flex; align-items: center; gap: 6px; padding: 2px 0; cursor: pointer; font-size: 11px; }
.search-btn { width: 100%; margin-top: 12px; padding: 7px 0; background: var(--primary); color: #fff; border: none; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600; cursor: pointer; transition: background 0.15s; }
.search-btn:hover { background: var(--primary-hover); }
.search-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.search-input { width: 100%; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; font-size: 12px; margin-bottom: 10px; outline: none; transition: border-color 0.15s ease, box-shadow 0.15s ease; }
.search-input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.results-info { font-weight: 600; margin-bottom: 10px; font-size: 11px; }
.time-text { color: var(--text-muted); font-weight: 400; }
.rewritten-queries {
  margin: -2px 0 10px;
  color: var(--text-muted);
  font-size: 11px;
}
.result-card { min-width: 0; overflow: hidden; background: var(--light); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px; margin-bottom: 8px; }
.result-card.top { background: #f0fdf4; border-color: #bbf7d0; }
.result-card.selected { border-color: #93c5fd; background: #eff6ff; }
.result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; gap: 8px; }
.result-check { display: flex; align-items: center; flex-shrink: 0; }
.result-check input { cursor: pointer; }
.result-doc { font-weight: 600; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-score { font-weight: 700; font-size: 13px; flex-shrink: 0; }
.result-modality { border: 1px solid rgba(14, 165, 233, 0.28); border-radius: 6px; background: rgba(14, 165, 233, 0.08); color: #0369a1; flex-shrink: 0; font-size: 11px; font-weight: 700; padding: 2px 6px; }
.result-text { color: var(--gray); line-height: 1.5; margin-bottom: 4px; word-break: break-word; }
.result-text :deep(mark.hl) { background: #fef08a; color: inherit; padding: 0 2px; border-radius: 2px; }
.result-thumb { max-width: 120px; max-height: 90px; border: 1px solid var(--border); border-radius: 6px; margin: 2px 0 6px; cursor: zoom-in; display: block; object-fit: cover; background: #fff; }
.result-meta { color: var(--text-muted); font-size: 9px; line-height: 1.5; word-break: break-word; }
.save-case { margin-top: 12px; padding: 8px; border: 1px dashed var(--primary); border-radius: var(--radius-sm); text-align: center; color: var(--primary); font-size: 11px; cursor: pointer; transition: background 0.15s; }
.save-case:hover { background: #eff6ff; }
.compare-summary { margin-bottom: 12px; }
.compare-title { font-weight: 600; font-size: 11px; margin-bottom: 8px; }
.compare-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(80px, 1fr)); gap: 6px; margin-bottom: 8px; }
.compare-cell { background: var(--light); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 8px; text-align: center; }
.compare-cell.best { background: #f0fdf4; border-color: #bbf7d0; }
.compare-label { font-weight: 700; font-size: 10px; color: var(--slate); margin-bottom: 4px; }
.compare-metric { font-weight: 700; font-size: 13px; color: var(--primary); }
.compare-cell.best .compare-metric { color: #166534; }
.compare-sub { font-size: 9px; color: var(--text-muted); }
.compare-tabs { display: flex; gap: 4px; margin-bottom: 8px; flex-wrap: wrap; }
.compare-tab { padding: 4px 10px; border: 1px solid var(--border); border-radius: 14px; background: #fff; font-size: 10px; cursor: pointer; color: var(--text-muted); transition: all 0.15s; }
.compare-tab.active { background: var(--primary); color: #fff; border-color: var(--primary); }
.compare-tab:hover:not(.active) { border-color: var(--primary); color: var(--primary); }
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
  max-height: 280px;
  overflow-y: auto;
}
.prompt-line.dim { color: var(--text-muted); font-size: 9px; }
.prompt-cost { border-top: 1px solid var(--border); padding-top: 10px; font-family: -apple-system, sans-serif; }
.cost-row { display: flex; justify-content: space-between; padding: 2px 0; font-size: 10px; color: var(--text-muted); }
.cost-row.total { font-weight: 600; color: var(--cyan); }
.llm-btn {
  width: 100%;
  margin-top: 12px;
  padding: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.llm-btn:hover { opacity: 0.9; }
.llm-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.llm-error {
  margin-top: 8px;
  padding: 8px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: var(--radius-sm);
  color: #991b1b;
  font-size: 11px;
}
.llm-output-section {
  margin-top: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.llm-output-text {
  padding: 10px;
  font-size: 11px;
  color: var(--gray);
  line-height: 1.8;
  word-break: break-word;
  white-space: pre-wrap;
  max-height: 300px;
  overflow-y: auto;
  background: #fff;
}
.llm-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  padding: 6px 10px;
  background: var(--light);
  border-top: 1px solid var(--border);
  font-size: 10px;
  color: var(--text-muted);
}
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

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .debug-layout {
    grid-template-columns: 1fr;
    min-height: auto;
    max-width: 100%;
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
    overflow: hidden;
  }

  .debug-right {
    border-left: none;
    border-top: 1px solid var(--border);
    padding: 10px 12px;
    background: #fff;
    overflow: hidden;
  }

  .compare-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .search-input {
    font-size: 16px; /* 防止 iOS 缩放 */
  }

  .result-head {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: start;
  }

  .result-doc {
    white-space: normal;
    word-break: break-word;
  }

  .result-score {
    font-size: 11px;
  }

  .compare-summary,
  .prompt-block,
  .llm-output-section,
  .save-case {
    max-width: 100%;
    overflow: hidden;
  }

  .compare-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .compare-cell {
    min-width: 0;
    padding: 7px 6px;
  }

  .compare-label,
  .compare-metric,
  .compare-sub {
    word-break: break-word;
  }

  .compare-tabs {
    overflow-x: auto;
    flex-wrap: nowrap;
    padding-bottom: 3px;
  }

  .compare-tab {
    flex: 0 0 auto;
  }

  .rewritten-queries {
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: #f8fafc;
    line-height: 1.6;
    word-break: break-word;
  }

  .result-text {
    max-height: 180px;
    overflow-y: auto;
    padding-right: 2px;
  }

  .result-meta {
    padding-top: 4px;
    border-top: 1px solid rgba(148, 163, 184, 0.25);
  }

  .llm-meta {
    gap: 6px;
  }

  .llm-meta span {
    max-width: 100%;
    word-break: break-word;
  }

  .right-tab {
    min-height: 36px;
    font-size: 12px;
  }

  .llm-btn {
    margin-top: 0;
    min-height: 40px;
  }

  .prompt-full,
  .llm-output-text {
    max-height: 42vh;
    font-size: 11px;
  }

  .modal-actions {
    justify-content: stretch;
  }

  .modal-actions button {
    flex: 1;
  }
}
</style>
