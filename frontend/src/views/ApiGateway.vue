<template>
  <div>
    <div class="page-body">
      <div class="api-layout">
        <div class="api-left">
          <div class="api-section-title">📡 API 列表</div>
          <div class="api-nav-item" v-for="api in apis" :key="api.path"
            :class="{ active: activeApi === api.path }"
            @click="activeApi = api.path"
          >
            <span class="api-method" :class="api.method">{{ api.method }}</span>
            {{ api.path }}
          </div>
          <div class="api-key-box">
            <div class="api-key-title">🔑 API Key</div>
            <div class="api-key-value">sk-ragforge-xxxx</div>
          </div>
        </div>
        <div class="api-right" v-if="currentApi">
          <div class="api-detail-title">{{ currentApi.method }} /api/v1{{ currentApi.path }}</div>
          <p class="api-detail-desc">{{ currentApi.description }}</p>
          <div class="code-block" v-if="currentApi.request">
            <div class="code-comment">// Request</div>
            <pre class="code-json">{{ formatJson(currentApi.request) }}</pre>
          </div>
          <div class="code-block">
            <div class="code-comment">// Response</div>
            <pre class="code-json">{{ formatJson(currentApi.response) }}</pre>
          </div>
          <div class="integration-box">
            <span>🔗</span>
            <span><strong>Agent 集成示例：</strong>{{ currentApi.integration }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const activeApi = ref('/search')

const apis = [
  {
    method: 'POST', path: '/search',
    description: '混合检索接口 —— Agent 调用此接口获取知识上下文。支持向量检索 + BM25 关键词检索 + Rerank 重排序。',
    integration: 'CareerMate Agent 调用此接口获取 JD 要求 → 与简历匹配 → 生成个性化建议',
    request: { query: '2026年后端需要什么AI技能', kb_ids: ['jd_library', 'industry_kb'], strategy: 'hybrid_rerank', top_k: 8, rerank_top_n: 5 },
    response: { results: [{ doc: '字节JD.pdf', chunk_id: '#12', score: 0.94, content: '...有大模型应用开发经验...' }], latency_ms: 452, tokens_used: 1240 }
  },
  {
    method: 'POST', path: '/search/batch',
    description: '批量检索接口 —— 一次查询多个问题，用于评测和质量监控。',
    integration: 'CareerMate Agent 批量查询多个 JD 要求 → 逐条比对简历 → 生成综合匹配报告',
    request: { queries: ['后端需要什么技能？', '前端需要什么技能？'], kb_ids: ['jd_library'], strategy: 'hybrid_rerank', top_k: 8 },
    response: { results: [[{ doc: '字节JD.pdf', score: 0.94 }], [{ doc: '腾讯前端JD.pdf', score: 0.88 }]], total_latency_ms: 1020 }
  },
  {
    method: 'GET', path: '/kb/list',
    description: '知识库列表 —— 获取所有可用的知识库及其元信息。',
    integration: 'CareerMate Agent 获取可用知识库 → 根据求职者意向选择匹配的知识库进行检索',
    request: null,
    response: { kbs: [{ id: 'jd_library', name: '岗位 JD 库', docs: 156, chunks: 8200 }, { id: 'interview_q', name: '面试题库', docs: 420, chunks: 18500 }] }
  },
  {
    method: 'POST', path: '/kb/upload',
    description: '文档上传接口 —— 上传文档到指定知识库，自动触发解析和索引管道。',
    integration: '管理员通过此接口上传新的 JD 文档 → 自动解析入库 → CareerMate Agent 即可检索',
    request: { kb_id: 'jd_library', filename: 'new_jd.pdf', content: '<base64>' },
    response: { task_id: 'task_abc123', status: 'processing', estimated_seconds: 12 }
  },
  {
    method: 'GET', path: '/eval/run',
    description: '评测执行 —— 对指定评测集执行检索质量评估，返回命中率等指标。',
    integration: '定期自动调用此接口进行回归评测 → 命中率下降超过 3% 触发告警',
    request: null,
    response: { eval_id: 'eval_001', total: 100, top3_hit: 89, top5_hit: 93, avg_latency_ms: 452 }
  },
  {
    method: 'GET', path: '/metrics',
    description: '系统指标 —— 获取检索服务的实时性能指标和调用统计。',
    integration: '监控面板调用此接口展示实时 QPS 和延迟 → 异常时自动告警',
    request: null,
    response: { qps: 38, p95_latency_ms: 2800, total_requests_today: 3240, avg_cost_per_call: 0.008 }
  },
]

const currentApi = computed(() => apis.find(a => a.path === activeApi.value))

function formatJson(obj) {
  return JSON.stringify(obj, null, 2)
}
</script>

<style scoped>
.api-layout { display: grid; grid-template-columns: 200px 1fr; background: #fff; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; min-height: 400px; }
.api-left { background: #f8fafc; border-right: 1px solid var(--border); padding: 16px; font-size: 10px; }
.api-right { padding: 18px; font-size: 12px; }
.api-section-title { font-weight: 700; font-size: 12px; margin-bottom: 10px; }
.api-nav-item { padding: 7px 8px; border-radius: 5px; margin-bottom: 3px; cursor: pointer; display: flex; align-items: center; gap: 6px; color: var(--text-muted); transition: all 0.1s ease; }
.api-nav-item:hover { background: rgba(0,0,0,0.03); }
.api-nav-item.active { background: #eff6ff; color: var(--blue); }
.api-method { font-size: 8px; font-weight: 700; padding: 1px 5px; border-radius: 3px; font-family: 'SF Mono', Monaco, monospace; }
.api-method.POST { background: #dbeafe; color: #1d4ed8; }
.api-method.GET { background: #d1fae5; color: #065f46; }
.api-key-box { border-top: 1px solid var(--border); margin-top: 12px; padding-top: 12px; }
.api-key-title { font-weight: 600; font-size: 10px; margin-bottom: 6px; }
.api-key-value { background: #fff; border: 1px solid var(--border); border-radius: 5px; padding: 5px 8px; font-family: 'SF Mono', Monaco, monospace; font-size: 10px; }
.api-detail-title { font-weight: 600; font-size: 15px; margin-bottom: 6px; }
.api-detail-desc { font-size: 12px; color: var(--text-muted); margin-bottom: 14px; }
.code-block { background: var(--navy); border-radius: 8px; padding: 14px; font-family: 'SF Mono', Monaco, monospace; font-size: 10px; color: #e2e8f0; margin-bottom: 14px; line-height: 1.7; overflow-x: auto; }
.code-comment { color: var(--cyan); }
.code-json { margin: 0; white-space: pre-wrap; word-break: break-all; }
.integration-box { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; border: 1px solid var(--purple); border-radius: 7px; font-size: 11px; background: #faf5ff; }
.integration-box strong { color: var(--purple); }
</style>
