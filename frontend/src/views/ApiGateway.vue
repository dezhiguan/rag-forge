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
            <div class="api-key-title">🔑 API Keys</div>
            <div class="api-key-usage">
              请求头携带 <code>X-API-Key: &lt;Key&gt;</code> 即可调用
            </div>
            <div v-if="apiKeyLoading" class="key-loading">加载中…</div>
            <div v-for="key in apiKeys" :key="key.id" class="key-row">
              <div class="key-info">
                <div class="key-name">{{ key.keyName }}</div>
                <div class="key-value" :title="key.apiKey">{{ key.apiKey }}</div>
              </div>
              <div class="key-actions">
                <span
                  class="key-toggle"
                  :class="{ on: key.enabled }"
                  @click="onToggleKey(key)"
                  :title="key.enabled ? '点击停用' : '点击启用'"
                >
                  {{ key.enabled ? '✓' : '—' }}
                </span>
                <span class="key-copy" @click="onCopyKey(key.apiKey)" title="复制">📋</span>
                <span class="key-del" @click="onDeleteKey(key)" title="删除">✗</span>
              </div>
            </div>
            <button class="key-gen-btn" :disabled="apiKeyLoading" @click="onCreateKey">
              + 生成新 Key
            </button>
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
import { onMounted, ref, computed } from 'vue'
import { listApiKeys, createApiKey, enableApiKey, deleteApiKey } from '../api/apikey'

const activeApi = ref('/search')
const apiKeys = ref([])
const apiKeyLoading = ref(false)

const apis = [
  {
    method: 'POST', path: '/search',
    description: '核心检索接口，支持 vector / keyword / hybrid / full / rewrite 五种策略。',
    integration: '外部 Agent 调用此接口检索知识库，获取相关 Chunk 后组装 Prompt 回答用户问题。',
    request: { query: '什么是RocketMQ事务消息？', kbIds: [1], strategy: 'hybrid', topK: 5, vectorWeight: 0.55, rerankTopN: 5 },
    response: { results: [{ chunkId: 12, filename: '面试题库.pdf', chunkIndex: 2, content: '...RocketMQ事务消息...', finalScore: 0.0942 }], latencyMs: 452, strategy: 'hybrid' }
  },
  {
    method: 'GET', path: '/kb',
    description: '获取全部知识库列表，包含文档数、Chunk 数等统计。',
    integration: '调用方先查知识库列表拿到 kbId，再传参给 /search 限定检索范围。',
    request: null,
    response: [{ id: 1, name: '面试题库', docCount: 156, chunkCount: 8200, status: 'active' }]
  },
]

const currentApi = computed(() => apis.find(a => a.path === activeApi.value))

function formatJson(obj) {
  return JSON.stringify(obj, null, 2)
}

async function loadKeys() {
  apiKeyLoading.value = true
  try {
    const res = await listApiKeys()
    apiKeys.value = res.data ?? []
  } catch {
    apiKeys.value = []
  } finally {
    apiKeyLoading.value = false
  }
}

async function onCreateKey() {
  const name = prompt('请输入 Key 名称（例如：CareerMate-Prod）：')
  if (!name?.trim()) return
  try {
    await createApiKey(name.trim())
    await loadKeys()
  } catch {
    // error handled by interceptor
  }
}

async function onToggleKey(key) {
  try {
    await enableApiKey(key.id, !key.enabled)
    key.enabled = !key.enabled
  } catch {
    // error handled by interceptor
  }
}

async function onDeleteKey(key) {
  if (!confirm(`确定删除 API Key「${key.keyName}」？`)) return
  try {
    await deleteApiKey(key.id)
    apiKeys.value = apiKeys.value.filter(k => k.id !== key.id)
  } catch {
    // error handled by interceptor
  }
}

async function onCopyKey(text) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // fallback silently
  }
}

onMounted(() => {
  loadKeys()
})
</script>

<style scoped>
.api-layout { display: grid; grid-template-columns: 200px 1fr; background: #fff; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; min-height: 400px; }
.api-left { background: #f8fafc; border-right: 1px solid var(--border); padding: 16px; font-size: 10px; }
.api-right { padding: 18px; font-size: 12px; }
.api-section-title { font-weight: 700; font-size: 12px; margin-bottom: 10px; }
.api-nav-item { padding: 7px 8px; border-radius: var(--radius-sm); margin-bottom: 3px; cursor: pointer; display: flex; align-items: center; gap: 6px; color: var(--text-muted); transition: all 0.1s ease; }
.api-nav-item:hover { background: rgba(0,0,0,0.03); }
.api-nav-item.active { background: #eff6ff; color: var(--blue); }
.api-method { font-size: 8px; font-weight: 700; padding: 1px 5px; border-radius: var(--radius-sm); font-family: 'SF Mono', Monaco, monospace; }
.api-method.POST { background: #dbeafe; color: #1d4ed8; }
.api-method.GET { background: #d1fae5; color: #065f46; }
.api-key-box { border-top: 1px solid var(--border); margin-top: 12px; padding-top: 12px; }
.api-key-usage {
  margin-bottom: 6px;
  font-size: 9px;
  color: var(--text-muted);
  line-height: 1.6;
  padding: 6px 8px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.api-key-usage code {
  background: var(--light);
  padding: 1px 4px;
  border-radius: 3px;
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 8px;
}
.api-key-title { font-weight: 600; font-size: 10px; margin-bottom: 8px; }
.key-loading { font-size: 10px; color: var(--text-muted); }
.key-row { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 6px; padding: 6px; background: #fff; border: 1px solid var(--border); border-radius: var(--radius-sm); }
.key-info { min-width: 0; }
.key-name { font-size: 9px; font-weight: 600; color: var(--slate); }
.key-value { font-size: 8px; font-family: 'SF Mono', Monaco, monospace; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.key-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.key-toggle { cursor: pointer; font-size: 9px; font-weight: 700; padding: 2px 6px; border-radius: var(--radius-md); background: rgba(148,163,184,0.18); color: #64748b; }
.key-toggle.on { background: #dcfce7; color: #166534; }
.key-copy { cursor: pointer; font-size: 10px; opacity: 0.6; }
.key-copy:hover { opacity: 1; }
.key-del { cursor: pointer; font-size: 11px; color: var(--red); font-weight: 700; }
.key-del:hover { opacity: 0.7; }
.key-gen-btn { width: 100%; margin-top: 8px; padding: 5px 0; background: #fff; border: 1px dashed var(--blue); border-radius: var(--radius-sm); font-size: 10px; color: var(--blue); cursor: pointer; transition: background 0.15s; }
.key-gen-btn:hover { background: #eff6ff; }
.key-gen-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.api-detail-title { font-weight: 600; font-size: 15px; margin-bottom: 6px; }
.api-detail-desc { font-size: 12px; color: var(--text-muted); margin-bottom: 14px; }
.code-block { background: var(--navy); border-radius: var(--radius-sm); padding: 14px; font-family: 'SF Mono', Monaco, monospace; font-size: 10px; color: #e2e8f0; margin-bottom: 14px; line-height: 1.7; overflow-x: auto; }
.code-comment { color: var(--cyan); }
.code-json { margin: 0; white-space: pre-wrap; word-break: break-all; }
.integration-box { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; border: 1px solid var(--purple); border-radius: var(--radius-sm); font-size: 11px; background: #faf5ff; }
.integration-box strong { color: var(--purple); }

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .api-layout {
    grid-template-columns: 1fr;
  }

  .api-left {
    border-right: none;
    border-bottom: 1px solid var(--border);
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    align-items: center;
    padding: 10px;
  }

  .api-section-title {
    display: none;
  }

  .api-nav-item {
    font-size: 9px;
    padding: 5px 7px;
    margin-bottom: 0;
  }

  .api-key-box {
    border-top: none;
    margin-top: 0;
    padding-top: 0;
    margin-left: auto;
  }

  .api-right {
    padding: 14px;
  }

  .code-block {
    padding: 10px;
    font-size: 9px;
  }
}
</style>
