<template>
  <div class="page-body quality-case-page">
    <header class="case-hero">
      <div class="case-hero-main">
        <button class="btn-back" type="button" @click="goBack">
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M11.5 4.5 6.5 10l5 5.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>返回看板</span>
        </button>
        <div class="case-hero-copy">
          <p class="case-eyebrow">LLM-as-Judge 案例详情</p>
          <h1 class="case-title">评测案例 #{{ caseId }}</h1>
        </div>
      </div>
      <div v-if="caseDetail.createdAt" class="case-meta-pill">
        评测时间 {{ formatDateTime(caseDetail.createdAt) }}
      </div>
    </header>

    <div v-if="loading" class="state-card">
      <div class="state-spinner" aria-hidden="true" />
      <p>正在加载案例详情...</p>
    </div>

    <div v-else-if="errorMessage" class="error-card">
      <div class="error-icon" aria-hidden="true">!</div>
      <div class="error-copy">
        <strong>加载失败</strong>
        <p>{{ errorMessage }}</p>
      </div>
      <button class="btn-retry" type="button" @click="loadCaseDetail">重新加载</button>
    </div>

    <template v-else>
      <section class="panel score-panel">
        <div class="panel-head">
          <h2>评分卡</h2>
          <span v-if="bottleneckLabel(caseDetail.bottleneck) !== '—'" class="bottleneck-chip">
            {{ bottleneckLabel(caseDetail.bottleneck) }}
          </span>
        </div>
        <div class="panel-body">
          <div class="score-grid">
            <article class="score-card score-card--primary">
              <div class="score-card-title">综合分</div>
              <div class="score-card-value" :class="scoreClass(normalizedScores.overallScore)">
                {{ scoreText(normalizedScores.overallScore) }}
              </div>
            </article>
            <article class="score-card">
              <div class="score-card-title">答案忠实度</div>
              <div class="score-card-value" :class="scoreClass(normalizedScores.faithfulness)">
                {{ scoreText(normalizedScores.faithfulness) }}
              </div>
            </article>
            <article class="score-card">
              <div class="score-card-title">上下文精度</div>
              <div class="score-card-value" :class="scoreClass(normalizedScores.contextPrecision)">
                {{ scoreText(normalizedScores.contextPrecision) }}
              </div>
            </article>
            <article class="score-card">
              <div class="score-card-title">答案相关性</div>
              <div class="score-card-value" :class="scoreClass(normalizedScores.answerRelevance)">
                {{ scoreText(normalizedScores.answerRelevance) }}
              </div>
            </article>
            <article class="score-card bottleneck-card">
              <div class="score-card-title">瓶颈</div>
              <div class="score-card-value">{{ bottleneckLabel(caseDetail.bottleneck) }}</div>
            </article>
          </div>
        </div>
      </section>

      <section class="panel two-col">
        <article class="content-card">
          <div class="panel-head"><h2>Query 与答案</h2></div>
          <div class="panel-body">
            <div class="case-block">
              <h3>用户问题</h3>
              <div class="text-block mono-text">{{ caseDetail.query || '—' }}</div>
            </div>
            <div class="case-block">
              <h3>生成答案</h3>
              <div class="text-block answer-text">
                <template v-for="(part, index) in answerSegments" :key="`ans-${index}`">
                  <span v-if="part.kind === 'text'">{{ part.value }}</span>
                  <span v-else class="citation-badge">{{ part.value }}</span>
                </template>
              </div>
            </div>
          </div>
        </article>

        <article class="content-card">
          <div class="panel-head">
            <h2>检索 Chunks</h2>
            <span class="panel-count">{{ chunks.length }} 条</span>
          </div>
          <div class="panel-body chunk-panel">
            <div v-if="!chunks.length" class="inline-empty">暂无检索 chunks</div>
            <article v-else class="chunk-card" v-for="chunk in chunks" :key="chunk.chunkId">
              <div class="chunk-card-head">
                <span v-if="chunk.index != null" class="chunk-ref" title="对应答案中的引用角标">[{{ chunk.index }}]</span>
                <span class="chunk-id">Chunk #{{ chunk.chunkId }}</span>
                <span v-if="chunk.score != null" class="chunk-score">
                  分数 <strong>{{ formatChunkScore(chunk.score) }}</strong>
                </span>
                <span v-if="chunk.relevant != null" :class="chunkRelevantClass(chunk.relevant)">
                  {{ chunk.relevant ? 'RELEVANT' : '⚠ 不相关' }}
                </span>
              </div>
              <p>{{ chunk.content || chunk.snippet || '（chunk 内容为空）' }}</p>
              <img
                v-if="chunk.imageUrl"
                :src="chunk.imageUrl"
                class="chunk-thumb"
                alt="chunk 图片预览"
                title="点击查看大图"
                @click="previewImage = chunk.imageUrl"
              >
            </article>
          </div>
        </article>
      </section>

      <section class="panel judge-panel">
        <div class="panel-head">
          <h2>DeepSeek 裁判分析</h2>
        </div>
        <div class="panel-body">
          <details class="judge-details" open>
            <summary>查看裁判 reasoning 与建议</summary>
            <div class="reasoning-block">
              <h3>Reasoning</h3>
              <p>{{ judgeReasoning || '暂无裁判说明' }}</p>
            </div>
            <div class="reasoning-block" v-if="improvements.length">
              <h3>改进建议</h3>
              <ul>
                <li v-for="(item, index) in improvements" :key="`imp-${index}`">{{ item }}</li>
              </ul>
            </div>
          </details>
        </div>
      </section>
    </template>
    <ImageLightbox :src="previewImage" @close="previewImage = ''" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCaseDetail } from '../api/quality'
import { bottleneckLabel, resolveHttpError } from '../api/error-messages'
import { useAuth } from '../composables/useAuth'
import ImageLightbox from '../components/ImageLightbox.vue'

const { clearSession } = useAuth()
const previewImage = ref('')
const router = useRouter()
const route = useRoute()

const caseId = route.params.id

const loading = ref(false)
const errorMessage = ref('')

const caseDetail = reactive({
  answerLogId: null,
  judgeResultId: null,
  query: '',
  answer: '',
  chunks: [],
  scores: {},
  judgeReasoning: '',
  improvements: [],
  bottleneck: '',
  createdAt: null,
})

const chunks = computed(() => caseDetail.chunks || [])
const normalizedScores = computed(() => {
  const raw = caseDetail.scores || {}
  return {
    overallScore: raw.overallScore ?? raw.overall,
    faithfulness: raw.faithfulness,
    contextPrecision: raw.contextPrecision,
    answerRelevance: raw.answerRelevance,
  }
})

const judgeReasoning = computed(() => caseDetail.judgeReasoning || '')
const improvements = computed(() => Array.isArray(caseDetail.improvements) ? caseDetail.improvements : [])

const answerSegments = computed(() => {
  const text = caseDetail.answer || ''
  const regex = /(\[[1-9]\d*\])/g
  const result = []
  let lastIndex = 0
  let match
  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      result.push({ kind: 'text', value: text.slice(lastIndex, match.index) })
    }
    result.push({ kind: 'cite', value: match[1] })
    lastIndex = match.index + match[1].length
  }
  if (lastIndex < text.length) {
    result.push({ kind: 'text', value: text.slice(lastIndex) })
  }
  return result
})

function unwrapResponse(resp) {
  const body = resp?.data != null ? resp.data : resp
  if (body && typeof body === 'object' && body.code === 200 && 'data' in body) {
    return body.data
  }
  return body || {}
}

function parseError(error) {
  const status = error?.response?.status || error?.status || (typeof error?.code === 'number' ? error.code : undefined)
  if (status === 401) {
    clearSession()
    router.push('/login')
    return '登录已失效，请重新登录'
  }
  const wrapped = error?.response ? error : { response: { status, data: error } }
  return resolveHttpError(wrapped, { kind: 'case' })
}

function scoreText(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return num.toFixed(2)
}

function formatChunkScore(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return num.toFixed(2)
}

function formatDateTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function scoreClass(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return ''
  if (num >= 0.8) return 'score-green'
  if (num >= 0.6) return 'score-amber'
  return 'score-red'
}

function chunkRelevantClass(relevant) {
  return relevant ? 'badge-green' : 'badge-off'
}

function goBack() {
  let query = {}
  try {
    const saved = sessionStorage.getItem('qualityDashboardQuery')
    if (saved) query = JSON.parse(saved)
  } catch {
    query = {}
  }
  router.push({ path: '/evaluation/quality', query })
}

async function loadCaseDetail() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetchCaseDetail(caseId)
    const data = unwrapResponse(response)
    caseDetail.answerLogId = data.answerLogId
    caseDetail.judgeResultId = data.judgeResultId
    caseDetail.query = data.query || ''
    caseDetail.answer = data.answer || ''
    caseDetail.chunks = Array.isArray(data.chunks) ? data.chunks : []
    caseDetail.scores = data.scores || {}
    caseDetail.judgeReasoning = data.judgeReasoning || ''
    caseDetail.improvements = Array.isArray(data.improvements) ? data.improvements : []
    caseDetail.bottleneck = data.bottleneck || ''
    caseDetail.createdAt = data.createdAt || null
  } catch (err) {
    errorMessage.value = parseError(err)
  } finally {
    loading.value = false
  }
}

onMounted(loadCaseDetail)
</script>

<style scoped>
.quality-case-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.case-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(15, 118, 110, 0.08), rgba(37, 99, 235, 0.05)),
    #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.case-hero-main {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  min-width: 0;
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 8px 14px 8px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
  background: #fff;
  color: #334155;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.btn-back svg {
  width: 16px;
  height: 16px;
}

.btn-back:hover {
  color: #0f766e;
  border-color: #99f6e4;
  background: #f0fdfa;
  transform: translateX(-1px);
}

.case-eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  color: #64748b;
  letter-spacing: 0.04em;
}

.case-title {
  margin: 0;
  font-size: 22px;
  line-height: 1.2;
  color: #0f172a;
}

.case-meta-pill {
  flex-shrink: 0;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(148, 163, 184, 0.35);
  color: #64748b;
  font-size: 12px;
  white-space: nowrap;
}

.state-card,
.error-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 14px;
  border: 1px solid var(--border);
  background: #fff;
}

.state-card {
  justify-content: center;
  color: #64748b;
  font-size: 14px;
}

.state-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid #cbd5e1;
  border-top-color: #0f766e;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

.error-card {
  border-color: #fecaca;
  background: linear-gradient(180deg, #fff5f5, #fff);
}

.error-icon {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #fee2e2;
  color: #b91c1c;
  font-weight: 700;
  flex-shrink: 0;
}

.error-copy {
  flex: 1;
  min-width: 0;
}

.error-copy strong {
  display: block;
  color: #991b1b;
  margin-bottom: 4px;
}

.error-copy p {
  margin: 0;
  color: #b91c1c;
  font-size: 13px;
}

.btn-retry {
  flex-shrink: 0;
  padding: 8px 14px;
  border: 1px solid #fca5a5;
  border-radius: 10px;
  background: #fff;
  color: #b91c1c;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.btn-retry:hover {
  background: #fef2f2;
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
  padding: 14px 16px;
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
  margin: 0;
}

.panel-count {
  font-size: 12px;
  color: #94a3b8;
}

.bottleneck-chip {
  padding: 4px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 500;
}

.score-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.score-card {
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
}

.score-card--primary {
  background: linear-gradient(180deg, #f0fdfa, #ecfeff);
  border-color: #99f6e4;
}

.score-card-title {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.score-card-value {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.score-green { color: #16a34a; }
.score-amber { color: #d97706; }
.score-red { color: #dc2626; }

.two-col {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 14px;
}

.content-card {
  min-width: 0;
}

.case-block + .case-block {
  margin-top: 14px;
}

.case-block h3 {
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  margin: 0 0 8px;
  letter-spacing: 0.02em;
}

.text-block {
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
}

.mono-text {
  font-family: SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 13px;
  white-space: pre-wrap;
  color: #0f172a;
}

.answer-text {
  white-space: pre-wrap;
  color: #1e293b;
  line-height: 1.7;
}

.citation-badge {
  display: inline-flex;
  margin: 0 2px;
  padding: 0 7px;
  border-radius: 999px;
  background: #e0f2fe;
  color: #0369a1;
  font-weight: 700;
  font-size: 12px;
}

.chunk-panel {
  max-height: 460px;
  overflow: auto;
}

.inline-empty {
  color: #94a3b8;
  font-size: 13px;
  text-align: center;
  padding: 28px 0;
}

.chunk-card {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
  background: #fff;
}

.chunk-card:last-child {
  margin-bottom: 0;
}

.chunk-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.chunk-ref {
  display: inline-flex;
  align-items: center;
  min-width: 24px;
  height: 22px;
  padding: 0 7px;
  justify-content: center;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
  border-radius: 6px;
}

.chunk-id {
  color: #334155;
  font-size: 12px;
  font-weight: 600;
}

.chunk-score {
  color: #64748b;
  font-size: 12px;
}

.chunk-card p {
  font-size: 13px;
  color: #475569;
  white-space: pre-wrap;
  line-height: 1.6;
  margin: 0;
}
.chunk-thumb {
  max-width: 160px;
  max-height: 120px;
  margin-top: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  cursor: zoom-in;
  object-fit: cover;
  background: #fff;
}

.badge-green {
  color: #166534;
  background: #dcfce7;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
}

.badge-off {
  color: #b91c1c;
  background: #fee2e2;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
}

.judge-details {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
  overflow: hidden;
}

.judge-details summary {
  cursor: pointer;
  padding: 12px 14px;
  font-size: 13px;
  font-weight: 500;
  color: #334155;
  list-style: none;
}

.judge-details summary::-webkit-details-marker {
  display: none;
}

.reasoning-block {
  padding: 0 14px 14px;
  color: #334155;
}

.reasoning-block h3 {
  margin: 0 0 6px;
  font-size: 12px;
  color: #64748b;
}

.reasoning-block p {
  margin: 0;
  line-height: 1.7;
  white-space: pre-wrap;
}

.reasoning-block ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.7;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1024px) {
  .score-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .two-col {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .case-hero {
    flex-direction: column;
  }

  .case-hero-main {
    flex-direction: column;
  }

  .score-grid {
    grid-template-columns: 1fr;
  }

  .error-card {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
