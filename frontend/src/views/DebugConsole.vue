<template>
  <div>
    <div class="page-body">
      <div class="debug-layout">
        <div class="debug-left">
          <div class="panel-title">⚙️ 检索参数</div>
          <div class="param-row">
            <div class="param-label">知识库</div>
            <select v-model="config.kb" class="param-select">
              <option v-for="kb in kbList" :key="kb" :value="kb">{{ kb }}</option>
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
          <button class="search-btn" @click="doSearch">🔍 检索</button>
        </div>

        <div class="debug-center">
          <input class="search-input" v-model="query" placeholder="输入检索查询..." @keyup.enter="doSearch">
          <div class="results-info">
            检索结果 · 耗时 <span class="time-text">BM25 142ms + Vector 238ms + Rerank 310ms</span>
          </div>
          <div class="result-card top" v-for="(r, i) in results" :key="i">
            <div class="result-head">
              <span class="result-doc">📄 {{ r.doc }}</span>
              <span class="result-score" :style="{ color: r.score >= 0.9 ? '#10b981' : r.score >= 0.8 ? '#f59e0b' : 'var(--text-muted)' }">Score {{ r.score.toFixed(2) }}</span>
            </div>
            <div class="result-text">"...{{ r.content.slice(0, 60) }}..."</div>
            <div class="result-meta">向量 {{ r.vectorScore }} | BM25 {{ r.bm25Score }} | Rerank {{ r.rerankScore }}</div>
          </div>
          <div class="save-case" @click="$router.push('/eval')">💾 保存为评测用例 →</div>
        </div>

        <div class="debug-right">
          <div class="panel-title">Prompt 预览</div>
          <div class="prompt-block">
            <div class="prompt-line dim">System: 你是RAG知识引擎，只基于Context回答。</div>
            <div class="prompt-line dim" style="margin-top:6px;">Context:</div>
            <div class="prompt-line" v-for="(r, i) in results.slice(0, config.rerankTopN)" :key="i">[{{ i + 1 }}] {{ r.doc }}: ...{{ r.content.slice(0, 40) }}...</div>
          </div>
          <div class="prompt-cost">
            <div class="cost-row"><span>Prompt</span><span>{{ promptTokens }} tk</span></div>
            <div class="cost-row"><span>Completion</span><span>310 tk</span></div>
            <div class="cost-row total"><span>预估成本</span><span>¥0.006</span></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'

const query = ref('2026年后端开发需要掌握哪些AI技能？')
const kbList = ['岗位 JD 库', '面试题库', '行业知识库', '求职策略库', '公司信息库']

const config = reactive({
  kb: '岗位 JD 库', topK: 8, vectorWeight: 0.55, rerankTopN: 5, compareMode: '单策略',
})

const compareModes = ['单策略', 'A/B 对比', '四路对比']

const baseResults = [
  { doc: '字节后端JD.pdf #12', content: '有大模型应用开发经验者优先，熟悉LangChain/LlamaIndex等框架，了解RAG架构设计，有Prompt Engineering实践经验...', score: 0.94, vectorScore: 0.94, bm25Score: 0.91, rerankScore: 0.96 },
  { doc: '美团后端JD.pdf #8', content: '了解AI/ML基础知识，能参与智能业务系统开发，对新技术保持好奇心，愿意学习和应用AI技术到实际业务场景...', score: 0.85, vectorScore: 0.82, bm25Score: 0.88, rerankScore: 0.85 },
  { doc: '腾讯JD.pdf #21', content: '负责后台服务开发，优化系统架构，有大规模分布式系统开发经验，熟悉微服务架构设计...', score: 0.71, vectorScore: 0.68, bm25Score: 0.74, rerankScore: 0.71 },
]

const results = ref(baseResults.map(r => ({ ...r })))

const promptTokens = computed(() => {
  return results.value.slice(0, config.rerankTopN).reduce((s, r) => s + Math.floor(r.content.length / 2), 0) + 180
})

function doSearch() {
  const w = config.vectorWeight
  results.value = baseResults.map(r => ({
    ...r,
    score: +(r.vectorScore * w + r.bm25Score * (1 - w)).toFixed(2),
  })).sort((a, b) => b.score - a.score)
}
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
.search-input { width: 100%; padding: 10px 12px; border: 1px solid var(--border); border-radius: 7px; font-size: 12px; margin-bottom: 10px; outline: none; }
.search-input:focus { border-color: var(--blue); }
.results-info { font-weight: 600; margin-bottom: 10px; font-size: 11px; }
.time-text { color: var(--text-muted); font-weight: 400; }
.result-card { background: var(--light); border: 1px solid var(--border); border-radius: 7px; padding: 10px; margin-bottom: 8px; }
.result-card.top { background: #f0fdf4; border-color: #bbf7d0; }
.result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.result-doc { font-weight: 600; }
.result-score { font-weight: 700; font-size: 13px; }
.result-text { color: var(--gray); line-height: 1.5; margin-bottom: 4px; }
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
