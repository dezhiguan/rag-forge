<template>
  <div class="page-body quality-case-page">
    <div class="case-toolbar">
      <button class="btn-ghost" @click="goBack">← 返回看板</button>
      <div class="case-id">Case ID: {{ caseId }}</div>
    </div>

    <div v-if="loading" class="state-hint">加载中...</div>
    <div v-else-if="errorMessage" class="quality-error-banner">{{ errorMessage }}</div>
    <template v-else>
      <section class="panel">
        <div class="panel-head">
          <h2>评分卡</h2>
        </div>
        <div class="panel-body">
          <div class="score-grid">
            <article class="score-card">
              <div class="score-card-title">综合分</div>
              <div class="score-card-value" :class="scoreClass(scores.overallScore)">{{ scoreText(scores.overallScore) }}</div>
            </article>
            <article class="score-card">
              <div class="score-card-title">答案忠实度</div>
              <div class="score-card-value" :class="scoreClass(scores.faithfulness)">{{ scoreText(scores.faithfulness) }}</div>
            </article>
            <article class="score-card">
              <div class="score-card-title">上下文精度</div>
              <div class="score-card-value" :class="scoreClass(scores.contextPrecision)">{{ scoreText(scores.contextPrecision) }}</div>
            </article>
            <article class="score-card">
              <div class="score-card-title">答案相关性</div>
              <div class="score-card-value" :class="scoreClass(scores.answerRelevance)">{{ scoreText(scores.answerRelevance) }}</div>
            </article>
            <article class="score-card bottleneck-card">
              <div class="score-card-title">瓶颈</div>
              <div class="score-card-value">{{ bottleneckLabel(caseDetail.bottleneck) }}</div>
            </article>
          </div>
        </div>
      </section>

      <section class="panel two-col">
        <article>
          <div class="panel-head"><h2>Query 与答案</h2></div>
          <div class="panel-body">
            <div class="case-block">
              <h3>Query</h3>
              <p class="mono-text">{{ caseDetail.query || '—' }}</p>
            </div>
            <div class="case-block">
              <h3>Generated Answer</h3>
              <p class="answer-text">
                <template v-for="(part, index) in answerSegments" :key="`ans-${index}`">
                  <span v-if="part.kind === 'text'">{{ part.value }}</span>
                  <span v-else class="citation-badge">{{ part.value }}</span>
                </template>
              </p>
            </div>
          </div>
        </article>

        <article>
          <div class="panel-head"><h2>检索 Chunks</h2></div>
          <div class="panel-body chunk-panel">
            <div v-if="!chunks.length" class="state-hint">暂无检索 chunks</div>
            <article v-else class="chunk-card" v-for="chunk in chunks" :key="chunk.chunkId">
              <div class="chunk-card-head">
                <span>Chunk {{ chunk.chunkId }}</span>
                <span>
                  score: <strong>{{ Number.isFinite(Number(chunk.score)) ? Number(chunk.score).toFixed(2) : '—' }}</strong>
                </span>
                <span :class="chunkRelevantClass(chunk.relevant)">
                  {{ chunk.relevant ? 'RELEVANT' : '⚠ 不相关' }}
                </span>
              </div>
              <p>{{ chunk.content || '（chunk 内容为空）' }}</p>
            </article>
          </div>
        </article>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>DeepSeek 裁判分析</h2>
        </div>
        <div class="panel-body">
          <details>
            <summary>查看裁判 reasoning 与建议</summary>
            <div class="reasoning-block">
              <h3>Reasoning</h3>
              <p>{{ judgeReasoning }}</p>
            </div>
            <div class="reasoning-block" v-if="improvements.length">
              <h3>Improvements</h3>
              <ul>
                <li v-for="(item, index) in improvements" :key="`imp-${index}`">{{ item }}</li>
              </ul>
            </div>
          </details>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCaseDetail } from '../api/quality'
import { bottleneckLabel, resolveHttpError } from '../api/error-messages'
import { useAuth } from '../composables/useAuth'

const { clearSession } = useAuth()
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
})

const chunks = computed(() => caseDetail.chunks || [])
const scores = computed(() => caseDetail.scores || {})

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
  if (body && body.code === 200 && 'data' in body) {
    return body.data
  }
  return body || {}
}

function parseError(error) {
  const status = error?.response?.status || error?.status
  if (status === 401) {
    clearSession()
    router.push('/login')
    return '登录已失效，请重新登录'
  }
  return resolveHttpError(error, { kind: 'case' })
}

function scoreText(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return num.toFixed(2)
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
  gap: 12px;
}

.case-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.case-id {
  color: var(--text-muted);
  font-size: 13px;
}

.panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.panel-head,
.panel-body {
  padding: 12px 14px;
}

.panel-head {
  border-bottom: 1px solid var(--border);
}

.panel h2 {
  font-size: 14px;
}

.score-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
}

.score-card {
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px;
}

.score-card-title {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.score-card-value {
  font-size: 22px;
  font-weight: 700;
}

.score-green { color: #16a34a; }
.score-amber { color: #d97706; }
.score-red { color: #dc2626; }

.bottleneck-card { background: #eff6ff; }

.two-col {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 12px;
}

.case-block {
  margin-bottom: 12px;
}

.case-block h3 {
  color: #334155;
  font-size: 12px;
  margin-bottom: 6px;
}

.mono-text {
  font-family: SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 13px;
  white-space: pre-wrap;
}

.answer-text {
  white-space: pre-wrap;
}

.citation-badge {
  display: inline-flex;
  margin: 0 2px;
  padding: 0 6px;
  border-radius: 999px;
  background: #e0f2fe;
  color: #0369a1;
  font-weight: 700;
}

.chunk-panel {
  max-height: 420px;
  overflow: auto;
}

.chunk-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 8px;
}

.chunk-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: #334155;
  font-size: 12px;
  margin-bottom: 6px;
}

.chunk-card p {
  font-size: 13px;
  color: #475569;
  white-space: pre-wrap;
}

.badge-off {
  color: #b91c1c;
  background: #fee2e2;
  border-radius: 999px;
  padding: 2px 6px;
  font-size: 11px;
}

.reasoning-block {
  margin-top: 10px;
  color: #334155;
}

.reasoning-block h3 {
  margin-bottom: 4px;
  font-size: 13px;
}

.reasoning-block ul {
  padding-left: 16px;
}

.quality-error-banner {
  background: #fee2e2;
  color: #991b1b;
  border: 1px solid #fecaca;
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 13px;
}

@media (max-width: 1024px) {
  .score-grid,
  .two-col {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 768px) {
  .score-grid,
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
