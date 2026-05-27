<template>
  <div>
    <div class="page-body">
      <div class="debug-layout">
        <div class="debug-left">
          <div class="panel-title">⚙️ 检索参数</div>
          <div class="param-row">
            <div class="param-label">知识库</div>
            <select v-model="config.kbId" class="param-select">
              <option :value="null">全部知识库</option>
              <option v-for="kb in kbList" :key="kb.id" :value="kb.id">
                {{ kb.name }}
              </option>
            </select>
          </div>
          <div class="param-row">
            <div class="param-label">Top-K</div>
            <div class="param-slider">
              <input type="range" v-model.number="config.topK" min="1" max="20">
              <span class="param-val">{{ config.topK }}</span>
            </div>
          </div>
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
          <div class="param-row">
            <div class="param-label">Rerank TopN</div>
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
            <span v-if="searched" class="time-text">
              · 耗时 Vector {{ latencyMs }}ms
            </span>
          </div>

          <div v-if="searching" class="state-hint">检索中…</div>
          <div v-else-if="searched && results.length === 0" class="state-hint">
            未找到匹配的文档块
          </div>
          <template v-else>
            <div
              v-for="(r, i) in results"
              :key="r.chunkId ?? i"
              class="result-card"
              :class="{ top: i === 0 && r.vectorScore >= 0.8 }"
            >
              <div class="result-head">
                <span class="result-doc">📄 {{ r.filename }} #{{ r.chunkIndex }}</span>
                <span
                  class="result-score"
                  :style="{ color: scoreColor(r.vectorScore) }"
                >
                  Score {{ r.vectorScore.toFixed(4) }}
                </span>
              </div>
              <div class="result-text" v-html="highlightContent(r.content, query)"></div>
              <div class="result-meta">向量 {{ r.vectorScore.toFixed(4) }}</div>
            </div>
          </template>

          <div v-if="results.length" class="save-case" @click="$router.push('/eval')">
            💾 保存为评测用例 →
          </div>
        </div>

        <div class="debug-right">
          <div class="panel-title">Prompt 预览</div>
          <div class="prompt-block">
            <div class="prompt-line dim">System: 你是RAG知识引擎，只基于Context回答。</div>
            <div class="prompt-line dim" style="margin-top:6px;">Context:</div>
            <div
              class="prompt-line"
              v-for="(r, i) in results.slice(0, config.rerankTopN)"
              :key="r.chunkId ?? i"
            >
              [{{ i + 1 }}] {{ r.filename }} #{{ r.chunkIndex }}: ...{{ excerpt(r.content, 40) }}...
            </div>
          </div>
          <div class="prompt-cost">
            <div class="cost-row"><span>Prompt</span><span>{{ promptTokens }} tk</span></div>
            <div class="cost-row"><span>Completion</span><span>—</span></div>
            <div class="cost-row total"><span>预估成本</span><span>—</span></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { listKb } from '../api/kb'
import { search as searchApi } from '../api/search'

const query = ref('')
const kbList = ref([])
const results = ref([])
const searching = ref(false)
const searched = ref(false)
const latencyMs = ref(0)
const strategy = ref('')

const config = reactive({
  kbId: null,
  topK: 8,
  vectorWeight: 0.55,
  rerankTopN: 5,
  compareMode: '单策略',
})

const compareModes = ['单策略', 'A/B 对比', '四路对比']

const promptTokens = computed(() => {
  return results.value.slice(0, config.rerankTopN).reduce((s, r) => s + Math.floor((r.content?.length || 0) / 2), 0) + 180
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

function scoreColor(score) {
  if (score >= 0.9) return '#10b981'
  if (score >= 0.8) return '#f59e0b'
  return '#64748b'
}

async function doSearch() {
  const q = query.value.trim()
  if (!q) {
    alert('请输入检索查询')
    return
  }

  searching.value = true
  searched.value = false
  try {
    const payload = { query: q, topK: config.topK }
    if (config.kbId != null) {
      payload.kbIds = [config.kbId]
    }
    const res = await searchApi(payload)
    const data = res.data ?? {}
    results.value = (data.results ?? []).slice().sort((a, b) => b.vectorScore - a.vectorScore)
    latencyMs.value = data.latencyMs ?? 0
    strategy.value = data.strategy ?? 'vector'
    searched.value = true
  } catch {
    results.value = []
    searched.value = true
  } finally {
    searching.value = false
  }
}

onMounted(async () => {
  try {
    const res = await listKb()
    kbList.value = res.data ?? []
  } catch {
    kbList.value = []
  }
})
</script>

<style scoped>
.debug-layout { display: grid; grid-template-columns: 220px 1fr 200px; background: #fff; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; min-height: 420px; }
.debug-left { background: #f8fafc; border-right: 1px solid var(--border); padding: 16px; font-size: 11px; }
.debug-center { padding: 16px; font-size: 11px; }
.debug-right { background: #f8fafc; border-left: 1px solid var(--border); padding: 16px; font-size: 10px; font-family: 'SF Mono', Monaco, monospace; }
.panel-title { font-weight: 700; font-size: 12px; margin-bottom: 10px; color: var(--slate); }
.debug-right .panel-title { font-family: -apple-system, sans-serif; }
.param-row { margin-bottom: 10px; }
.param-label { font-size: 10px; color: var(--text-muted); margin-bottom: 3px; }
.param-select { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: 5px; font-size: 10px; background: #fff; color: var(--text); outline: none; }
.param-slider { display: flex; align-items: center; gap: 8px; }
.param-slider input[type="range"] { flex: 1; height: 4px; -webkit-appearance: none; background: var(--border); border-radius: 2px; outline: none; }
.param-slider input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 14px; height: 14px; background: var(--blue); border-radius: 50%; cursor: pointer; }
.param-val { font-size: 11px; font-weight: 600; color: var(--text); min-width: 28px; text-align: right; }
.divider { border-top: 1px solid var(--border); margin: 12px 0; }
.radio-row { display: flex; align-items: center; gap: 6px; padding: 2px 0; cursor: pointer; font-size: 11px; }
.search-btn { width: 100%; margin-top: 12px; padding: 7px 0; background: var(--blue); color: #fff; border: none; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; transition: background 0.15s; }
.search-btn:hover { background: #2563eb; }
.search-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.search-input { width: 100%; padding: 10px 12px; border: 1px solid var(--border); border-radius: 7px; font-size: 12px; margin-bottom: 10px; outline: none; }
.search-input:focus { border-color: var(--blue); }
.results-info { font-weight: 600; margin-bottom: 10px; font-size: 11px; }
.time-text { color: var(--text-muted); font-weight: 400; }
.state-hint { text-align: center; color: var(--text-muted); padding: 32px 0; font-size: 12px; }
.result-card { background: var(--light); border: 1px solid var(--border); border-radius: 7px; padding: 10px; margin-bottom: 8px; }
.result-card.top { background: #f0fdf4; border-color: #bbf7d0; }
.result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; gap: 8px; }
.result-doc { font-weight: 600; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-score { font-weight: 700; font-size: 13px; flex-shrink: 0; }
.result-text { color: var(--gray); line-height: 1.5; margin-bottom: 4px; word-break: break-word; }
.result-text :deep(mark.hl) { background: #fef08a; color: inherit; padding: 0 2px; border-radius: 2px; }
.result-meta { color: var(--text-muted); font-size: 9px; }
.save-case { margin-top: 12px; padding: 8px; border: 1px dashed var(--blue); border-radius: 7px; text-align: center; color: var(--blue); font-size: 11px; cursor: pointer; transition: background 0.15s; }
.save-case:hover { background: #eff6ff; }
.prompt-block { margin-bottom: 12px; }
.prompt-line { color: var(--gray); line-height: 1.7; word-break: break-all; font-size: 9px; }
.prompt-line.dim { color: var(--text-muted); }
.prompt-cost { border-top: 1px solid var(--border); padding-top: 10px; font-family: -apple-system, sans-serif; }
.cost-row { display: flex; justify-content: space-between; padding: 2px 0; font-size: 10px; color: var(--text-muted); }
.cost-row.total { font-weight: 600; color: var(--cyan); }
</style>
