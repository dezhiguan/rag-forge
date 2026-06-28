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
                <div class="key-value" :title="key.apiKey || '创建后仅显示一次'">
                  {{ key.apiKey || '创建后仅显示一次' }}
                </div>
              </div>
              <div class="key-actions">
                <span class="key-action-wrap">
                  <Transition name="key-tip">
                    <span
                      v-if="keyTip?.keyId === key.id && keyTip.target === 'toggle'"
                      class="key-action-tip"
                    >
                      {{ keyTip.text }}
                    </span>
                  </Transition>
                  <span
                    class="key-toggle"
                    :class="{ on: key.enabled }"
                    @click="onToggleKey(key)"
                    :title="key.enabled ? '点击停用' : '点击启用'"
                  >
                    {{ key.enabled ? '✓' : '—' }}
                  </span>
                </span>
                <span class="key-action-wrap">
                  <Transition name="key-tip">
                    <span
                      v-if="keyTip?.keyId === key.id && keyTip.target === 'copy'"
                      class="key-action-tip"
                    >
                      {{ keyTip.text }}
                    </span>
                  </Transition>
                  <span class="key-copy" @click="onCopyKey(key)" title="复制">📋</span>
                </span>
                <span
                  class="key-del"
                  :class="{ 'is-disabled': !deleteEnabled }"
                  :title="deleteEnabled ? '删除' : '演示环境已禁用删除'"
                  @click="onDeleteKey(key)"
                >
                  ✗
                </span>
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
import { API_KEY_DELETE_ENABLED } from '../config/uiPolicy'
import { listApiKeys, createApiKey, enableApiKey, deleteApiKey } from '../api/apikey'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { useToast } from '../composables/useToast'

const toast = useToast()

const deleteEnabled = API_KEY_DELETE_ENABLED
const activeApi = ref('/search')
const apiKeys = ref([])
const apiKeyLoading = ref(false)
const keyTip = ref(null)
let keyTipTimer = null

const apis = [
  {
    method: 'POST', path: '/search',
    description: '核心检索接口，返回 chunks、分段耗时和策略信息；不直接生成最终答案。',
    integration: '外部 Agent 调用此接口检索知识库，拿到相关 Chunk 后自行组装上下文并生成回答。',
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
    const res = await createApiKey(name.trim())
    if (res.data) {
      apiKeys.value = [res.data, ...apiKeys.value]
      showKeyTip(res.data.id, '已生成，可复制', 'copy')
    } else {
      await loadKeys()
    }
  } catch {
    // error handled by interceptor
  }
}

function showKeyTip(keyId, text, target) {
  keyTip.value = { keyId, text, target }
  if (keyTipTimer) clearTimeout(keyTipTimer)
  keyTipTimer = setTimeout(() => {
    keyTip.value = null
    keyTipTimer = null
  }, 1500)
}

async function onToggleKey(key) {
  const nextEnabled = !key.enabled
  try {
    await enableApiKey(key.id, nextEnabled)
    key.enabled = nextEnabled
    showKeyTip(key.id, nextEnabled ? '已启用' : '已禁用', 'toggle')
  } catch {
    showKeyTip(key.id, '操作失败', 'toggle')
  }
}

async function onDeleteKey(key) {
  if (!deleteEnabled) return
  const ok = await confirmDialog({
    title: '删除 API Key',
    message: `确定删除 API Key「${key.keyName}」？`,
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteApiKey(key.id)
    apiKeys.value = apiKeys.value.filter(k => k.id !== key.id)
    toast.success('API Key 已删除')
  } catch {
    // 全局拦截器已 toast
  }
}

async function copyTextToClipboard(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  document.body.appendChild(textarea)
  textarea.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!ok) throw new Error('copy failed')
}

async function onCopyKey(key) {
  const text = key?.apiKey
  if (!text) {
    showKeyTip(key.id, '仅创建后可复制', 'copy')
    return
  }
  try {
    await copyTextToClipboard(text)
    showKeyTip(key.id, '复制成功', 'copy')
  } catch {
    showKeyTip(key.id, '复制失败', 'copy')
  }
}

onMounted(() => {
  loadKeys()
})
</script>

<style scoped>
.page-body { padding: 20px 24px; }
.api-layout { display: grid; grid-template-columns: 220px 1fr; background: #fff; border: 1px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); overflow: hidden; min-height: calc(100vh - 88px); }
.api-left { background: #f8fafc; border-right: 1px solid var(--border); padding: 16px; font-size: 10px; }
.api-right { padding: 18px; font-size: 12px; }
.api-section-title { font-weight: 700; font-size: 12px; margin-bottom: 10px; }
.api-nav-item { padding: 7px 8px; border-radius: var(--radius-sm); margin-bottom: 3px; cursor: pointer; display: flex; align-items: center; gap: 6px; color: var(--text-muted); transition: all 0.1s ease; }
.api-nav-item:hover { background: rgba(0,0,0,0.03); }
.api-nav-item.active { background: #eff6ff; color: var(--primary); }
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
.key-action-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
}
.key-action-tip {
  position: absolute;
  bottom: calc(100% + 4px);
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.92);
  color: #fff;
  font-size: 9px;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1.3;
  pointer-events: none;
  z-index: 2;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
}
.key-action-tip::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 4px solid transparent;
  border-top-color: rgba(15, 23, 42, 0.92);
}
.key-copy { cursor: pointer; font-size: 10px; opacity: 0.6; }
.key-copy:hover { opacity: 1; }
.key-tip-enter-active,
.key-tip-leave-active {
  transition: opacity 0.15s ease;
}
.key-tip-enter-from,
.key-tip-leave-to {
  opacity: 0;
}
.key-del { cursor: pointer; font-size: 11px; color: var(--red); font-weight: 700; }
.key-del:hover { opacity: 0.7; }
.key-del.is-disabled {
  color: var(--text-muted);
  opacity: 0.45;
  cursor: not-allowed;
  pointer-events: none;
}
.key-del.is-disabled:hover { opacity: 0.45; }
.key-gen-btn { width: 100%; margin-top: 8px; padding: 5px 0; background: #fff; border: 1px dashed var(--primary); border-radius: var(--radius-sm); font-size: 10px; color: var(--primary); cursor: pointer; transition: background 0.15s; }
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
    display: grid;
    grid-template-columns: 1fr;
    gap: 10px;
    align-items: stretch;
    padding: 10px;
  }

  .api-section-title {
    display: none;
  }

  .api-nav-item {
    min-height: 36px;
    font-size: 11px;
    padding: 7px 9px;
    margin-bottom: 0;
  }

  .api-key-box {
    border-top: 1px solid var(--border);
    margin-top: 2px;
    padding-top: 10px;
    margin-left: 0;
    min-width: 0;
  }

  .api-key-title {
    font-size: 12px;
  }

  .api-key-usage {
    font-size: 11px;
  }

  .key-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: flex-start;
    gap: 10px;
  }

  .key-actions {
    gap: 8px;
  }

  .key-toggle,
  .key-copy,
  .key-del {
    min-width: 28px;
    min-height: 28px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .key-name {
    font-size: 11px;
  }

  .key-value {
    max-width: calc(100vw - 126px);
    font-size: 10px;
  }

  .api-right {
    padding: 14px;
  }

  .code-block {
    max-width: 100%;
    padding: 10px;
    font-size: 10px;
  }

  .code-json {
    white-space: pre;
    word-break: normal;
  }

  .integration-box {
    font-size: 12px;
    line-height: 1.6;
  }
}
</style>
