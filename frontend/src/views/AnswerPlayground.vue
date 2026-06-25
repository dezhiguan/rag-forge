<template>
  <div class="answer-page">
    <section class="workbench">
      <aside class="answer-controls">
        <div class="control-title">应答参数</div>
        <label class="field">
          <span>知识库</span>
          <select v-model="form.kbId">
            <option disabled :value="null">选择知识库</option>
            <option v-for="kb in kbList" :key="kb.id" :value="kb.id">
              {{ kb.name }} · {{ kb.answerMode || 'OFF' }}
            </option>
          </select>
        </label>
        <label class="field">
          <span>检索策略</span>
          <select v-model="form.retrievalStrategy">
            <option value="hybrid">hybrid</option>
            <option value="vector">vector</option>
            <option value="keyword">keyword</option>
            <option value="rewrite">rewrite</option>
            <option value="full">full</option>
          </select>
        </label>
        <label class="field">
          <span>应答模式</span>
          <select v-model="form.answerMode">
            <option value="ON">ON</option>
            <option value="PREVIEW">PREVIEW</option>
          </select>
        </label>
        <label class="field compact">
          <span>Top-K</span>
          <input v-model.number="form.topK" type="number" min="1" max="30">
        </label>
        <label class="field compact">
          <span>Max Tokens</span>
          <input v-model.number="form.maxTokens" type="number" min="64" max="4000">
        </label>
        <button class="run-btn" :disabled="running || !form.kbId || !query.trim()" @click="runAnswer">
          {{ running ? '生成中...' : '生成应答' }}
        </button>
        <div v-if="error" class="error-box">{{ error }}</div>
      </aside>

      <main class="answer-main">
        <textarea
          v-model="query"
          class="query-box"
          placeholder="输入需要基于知识库回答的问题..."
          :disabled="running"
        />
        <div class="answer-grid">
          <section class="answer-panel">
            <div class="panel-head">
              <span>Answer</span>
              <small v-if="latency.total">total {{ latency.total }}ms</small>
            </div>
            <div class="answer-text" :class="{ empty: !answer }">
              {{ answer || '等待生成带引用的应答' }}
            </div>
          </section>

          <section class="answer-panel">
            <div class="panel-head">
              <span>Citations</span>
              <small>{{ citations.length }} refs</small>
            </div>
            <div v-if="!citations.length" class="empty-cites">暂无引用</div>
            <article v-for="citation in citations" :key="citation.id" class="citation-card">
              <img v-if="citation.imageUrl" :src="citation.imageUrl" alt="" class="citation-thumb">
              <div class="citation-body">
                <div class="citation-meta">
                  <b>[{{ citation.id }}]</b>
                  <span>{{ citation.modality }}</span>
                  <span>chunk {{ citation.chunkId }}</span>
                </div>
                <p>{{ citation.textSnippet }}</p>
              </div>
            </article>
          </section>
        </div>

        <section class="event-strip">
          <div class="panel-head">
            <span>SSE Events</span>
            <small v-if="retrieval.latencyMs">retrieval {{ retrieval.latencyMs }}ms</small>
          </div>
          <div class="events">
            <div v-for="(event, index) in events" :key="index" class="event-line">
              <span>{{ event.type }}</span>
              <code>{{ event.payload }}</code>
            </div>
          </div>
        </section>
      </main>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { listKb } from '../api/kb'
import { translateError } from '../api/request'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'

const { state } = useAuth()
const toast = useToast()

const ANSWER_DISABLED_HINT = '该知识库没有开启应答模式，请先到知识库开启应答模式'
const kbList = ref([])
const query = ref('广州 Java 25-30k 技术栈')
const answer = ref('')
const citations = ref([])
const events = ref([])
const error = ref('')
const running = ref(false)
const retrieval = reactive({ chunks: [], latencyMs: 0 })
const latency = reactive({ retrieval: 0, llm: 0, total: 0 })
const form = reactive({
  kbId: null,
  retrievalStrategy: 'hybrid',
  answerMode: 'ON',
  topK: 10,
  maxTokens: 800,
})

onMounted(async () => {
  const res = await listKb()
  kbList.value = res.data || []
  form.kbId = kbList.value[0]?.id ?? null
})

async function runAnswer() {
  running.value = true
  error.value = ''
  answer.value = ''
  citations.value = []
  events.value = []
  Object.assign(retrieval, { chunks: [], latencyMs: 0 })
  Object.assign(latency, { retrieval: 0, llm: 0, total: 0 })
  try {
    const response = await fetch('/api/v1/answer', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
        ...(state.accessToken ? { Authorization: `Bearer ${state.accessToken}` } : {}),
      },
      body: JSON.stringify({
        kbIds: [form.kbId],
        query: query.value,
        retrievalStrategy: form.retrievalStrategy,
        answerMode: form.answerMode,
        stream: true,
        topK: form.topK,
        maxTokens: form.maxTokens,
      }),
    })
    if (!response.ok) {
      const body = await response.text()
      if (isAnswerDisabledPayload(body)) {
        toast.error(ANSWER_DISABLED_HINT)
        return
      }
      throw new Error(translateError(parseErrorPayload(body)) || body || `HTTP ${response.status}`)
    }
    await readSse(response)
  } catch (e) {
    const raw = e?.message || '生成失败'
    if (isAnswerDisabledPayload(raw)) {
      toast.error(ANSWER_DISABLED_HINT)
      return
    }
    error.value = translateError(parseErrorPayload(raw)) || raw
  } finally {
    running.value = false
  }
}

async function readSse(response) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop() || ''
    for (const frame of frames) {
      handleFrame(frame)
    }
  }
}

function handleFrame(frame) {
  const event = frame.match(/^event:\s*(.+)$/m)?.[1] || 'message'
  const raw = frame.match(/^data:\s*(.+)$/m)?.[1] || '{}'
  events.value.push({ type: event, payload: raw })
  let data = {}
  try {
    data = JSON.parse(raw)
  } catch {
    data = {}
  }
  if (event === 'retrieval') {
    retrieval.chunks = data.chunks || []
    retrieval.latencyMs = data.latencyMs || 0
  } else if (event === 'token') {
    answer.value += data.delta || ''
  } else if (event === 'complete') {
    answer.value = data.answer || answer.value
    citations.value = data.citations || []
    Object.assign(latency, data.latency || {})
  } else if (event === 'error') {
    const code = data.error || data.code || ''
    if (code === 'ANSWER_DISABLED' || String(data.message || '').includes('ANSWER_DISABLED')) {
      toast.error(ANSWER_DISABLED_HINT)
      return
    }
    error.value = translateError({ msg: code, message: data.message }) || `${code} ${data.message || ''}`.trim()
  }
}

function parseErrorPayload(text) {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return { msg: text }
  }
}

function isAnswerDisabledPayload(text) {
  if (!text) return false
  const payload = parseErrorPayload(text)
  const code = payload?.msg || payload?.error || ''
  if (code === 'ANSWER_DISABLED') return true
  return String(text).includes('ANSWER_DISABLED')
}
</script>

<style scoped>
.answer-page { padding: 18px; }
.workbench {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
}
.answer-controls,
.answer-panel,
.event-strip {
  background: #fff;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
}
.answer-controls { padding: 14px; align-self: start; }
.control-title,
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 700;
  color: #172033;
  margin-bottom: 12px;
}
.panel-head small { color: #64748b; font-weight: 500; }
.field { display: grid; gap: 6px; margin-bottom: 12px; font-size: 12px; font-weight: 700; color: #475569; }
.field select,
.field input,
.query-box {
  width: 100%;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  padding: 9px 10px;
  background: #f8fafc;
  color: #172033;
  font: inherit;
}
.field.compact input { max-width: 120px; }
.run-btn {
  width: 100%;
  border: 0;
  border-radius: 7px;
  padding: 10px 12px;
  background: #0f766e;
  color: #fff;
  font-weight: 700;
  cursor: pointer;
}
.run-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.error-box {
  margin-top: 12px;
  padding: 10px;
  border-radius: 7px;
  background: #fee2e2;
  color: #991b1b;
  font-size: 12px;
}
.answer-main { display: grid; gap: 14px; }
.query-box { min-height: 104px; resize: vertical; background: #fff; }
.answer-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(280px, 0.9fr);
  gap: 14px;
}
.answer-panel,
.event-strip { padding: 14px; min-height: 260px; }
.answer-text {
  white-space: pre-wrap;
  color: #172033;
  line-height: 1.8;
}
.answer-text.empty,
.empty-cites { color: #94a3b8; }
.citation-card {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid #edf2f7;
}
.citation-card:first-of-type { border-top: 0; }
.citation-card:not(:has(img)) { grid-template-columns: 1fr; }
.citation-thumb {
  width: 86px;
  height: 64px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #dbe3ee;
  background: #f8fafc;
}
.citation-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
  margin-bottom: 4px;
}
.citation-meta b { color: #0f766e; }
.citation-body p { color: #334155; font-size: 13px; line-height: 1.6; }
.events { max-height: 190px; overflow: auto; display: grid; gap: 6px; }
.event-line { display: grid; grid-template-columns: 86px minmax(0, 1fr); gap: 8px; font-size: 12px; }
.event-line span { color: #0f766e; font-weight: 700; }
.event-line code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #475569;
}
@media (max-width: 980px) {
  .workbench,
  .answer-grid { grid-template-columns: 1fr; }
}
</style>
