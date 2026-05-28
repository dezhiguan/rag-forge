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
            <div class="api-key-value">sk-ragforge-dev</div>
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
    description: '检索接口 —— 支持 vector / keyword / hybrid / full（全链路 reranker）。',
    integration: 'CareerMate Agent 调用此接口获取 JD 要求 → 与简历匹配 → 生成个性化建议',
    request: { query: '2026年后端需要掌握哪些AI技能？', kbIds: [1], strategy: 'hybrid', topK: 8, vectorWeight: 0.55, rerankTopN: 5 },
    response: { results: [{ chunkId: 12, docId: 3, filename: '字节JD.pdf', chunkIndex: 2, content: '...有大模型应用开发经验...', finalScore: 0.0942 }], latencyMs: 452, strategy: 'hybrid' }
  },
  {
    method: 'GET', path: '/kb',
    description: '知识库列表 —— 获取知识库及文档/Chunk 计数。',
    integration: '管理后台用于展示知识库、上传文档、查看处理状态。',
    request: null,
    response: [{ id: 1, name: '面试题库', docCount: 156, chunkCount: 8200, status: 'active', createdAt: '2026-05-27 12:30:00' }]
  },
  {
    method: 'POST', path: '/kb',
    description: '创建知识库。',
    integration: '管理后台创建知识库并配置分块策略。',
    request: { name: '产品文档库', description: '可选', chunkSize: 512, chunkOverlap: 64 },
    response: { id: 1, name: '产品文档库', docCount: 0, chunkCount: 0, status: 'active' }
  },
  {
    method: 'POST', path: '/kb/1/documents',
    description: '上传文档到知识库（multipart/form-data）。支持 MD5 去重并提示覆盖。',
    integration: '管理后台上传 PDF/Markdown/Word/HTML，触发异步解析与索引。',
    request: null,
    response: { exists: false, documentId: 123, status: 'processing', message: '上传成功，正在处理' }
  },
  {
    method: 'GET', path: '/documents/123',
    description: '文档详情 + chunks 列表。',
    integration: '用于文档详情页展示分块结果与处理状态。',
    request: null,
    response: { id: 123, kbId: 1, filename: '某公司面经.pdf', parseStatus: 'completed', chunkCount: 80, chunks: [{ chunkIndex: 0, tokenCount: 512, content: '...' }] }
  },
  {
    method: 'GET', path: '/eval/datasets',
    description: '评测数据集列表。',
    integration: '评测实验室用于管理评测题库与实验。',
    request: null,
    response: [{ id: 1, name: '后端检索基准集', kbId: 1, questionCount: 100, createdAt: '2026-05-27 12:30:00' }]
  },
  {
    method: 'GET', path: '/metrics/dashboard',
    description: '驾驶舱指标（知识库、文档、Chunk、今日调用、平均延迟、Top3 命中率）。',
    integration: '管理后台驾驶舱展示运行态指标。',
    request: null,
    response: { kbCount: 6, documentCount: 1280, chunkCount: 52000, todayApiCalls: 3240, avgLatencyMs: 2800, hitRate: 0.912 }
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
