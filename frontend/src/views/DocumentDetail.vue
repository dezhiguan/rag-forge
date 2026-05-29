<template>
  <div>
    <div class="page-body">
      <div v-if="loading && !doc" class="state-hint">
        <div class="state-icon">⏳</div>
        <div class="state-title">加载中...</div>
      </div>

      <template v-else-if="doc">
        <header class="doc-header">
          <div class="doc-header-main">
            <h1 class="doc-title">{{ doc.filename }}</h1>
            <div class="doc-sub">
              <span>{{ formatBytes(doc.fileSize) }}</span>
              <span class="dot">·</span>
              <span>v{{ doc.version ?? 1 }}</span>
              <span class="dot">·</span>
              <span
                class="badge"
                :class="docStatusClass(doc.parseStatus)"
                :title="doc.parseStatus === 'failed' ? doc.errorMsg || '处理失败' : undefined"
              >
                <span v-if="isProcessing(doc.parseStatus)" class="status-icon spin">⟳</span>
                <span v-else-if="doc.parseStatus === 'completed'" class="status-icon ok">✓</span>
                {{ docStatusLabel(doc.parseStatus) }}
              </span>
            </div>
          </div>
        </header>

        <div v-if="doc.parseStatus === 'completed'" class="doc-layout">
          <div class="doc-left">
            <div class="section-title">📄 Chunks（{{ doc.chunks?.length ?? 0 }}）</div>
            <div v-if="!(doc.chunks?.length > 0)" class="state-hint" style="padding:24px 0">
              <div class="state-desc">文档处理完成后将显示分块数据</div>
            </div>
            <div v-else>
              <div v-for="c in doc.chunks" :key="c.chunkIndex" class="chunk-card">
                <div class="chunk-head">
                  <span class="chunk-title">#{{ c.chunkIndex }}</span>
                  <span class="chunk-tokens">{{ c.tokenCount ?? 0 }} tokens</span>
                </div>
                <div class="chunk-text" :title="c.content">{{ summarizeContent(c.content) }}</div>
              </div>
            </div>
          </div>

          <div class="doc-right">
            <div class="section-title">📊 文档元信息</div>
            <div class="meta-list">
              <div class="meta-row">
                <span class="meta-key">文件名</span>
                <span class="meta-val">{{ doc.filename }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">大小</span>
                <span class="meta-val">{{ formatBytes(doc.fileSize) }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">类型</span>
                <span class="meta-val">{{ doc.fileType }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">版本</span>
                <span class="meta-val">v{{ doc.version ?? 1 }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">总块数</span>
                <span class="meta-val">{{ doc.chunkCount ?? 0 }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">向量模型</span>
                <span class="meta-val">{{ doc.embeddingModel || '-' }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">块大小</span>
                <span class="meta-val">{{ doc.chunkSize != null ? doc.chunkSize + ' 字符' : '-' }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">块重叠</span>
                <span class="meta-val">{{ doc.chunkOverlap != null ? doc.chunkOverlap + ' 字符' : '-' }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">上传时间</span>
                <span class="meta-val">{{ formatTime(doc.createdAt) }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">知识库</span>
                <span class="meta-val">{{ doc.kbName || '-' }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">状态</span>
                <span class="meta-val">
                  <span class="badge badge-green">已完成</span>
                </span>
              </div>
            </div>
            <div class="search-action" @click="$router.push({ path: '/debug', query: { kbId: doc.kbId, docId: doc.id, docFilename: doc.filename } })">🔍 在此文档中检索 →</div>
          </div>
        </div>

        <div v-else-if="doc.parseStatus === 'failed'" class="processing-panel failed">
          <div class="processing-title">处理失败</div>
          <span class="status-icon fail">✗</span>
          <span class="badge badge-red">失败</span>
          <p class="processing-error">{{ doc.errorMsg || '未知错误' }}</p>
          <button class="btn-primary btn-retry" :disabled="retrying" @click="onReprocess">
            {{ retrying ? '提交中…' : '重试' }}
          </button>
        </div>

        <div v-else class="processing-panel">
          <div class="processing-spinner">⟳</div>
          <div class="processing-title">处理中，请稍候…</div>
          <div class="processing-status">
            当前阶段：
            <span class="badge badge-amber">{{ docStatusLabel(doc.parseStatus) }}</span>
          </div>
        </div>
      </template>

      <div v-else class="state-hint">
        <div class="state-icon">📄</div>
        <div class="state-title">文档不存在</div>
        <div class="state-desc">请检查链接或返回知识库列表</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getDocument, reprocessDocument } from '../api/document'
import { useDocumentPolling } from '../composables/useDocumentPolling'
import {
  docStatusClass,
  docStatusLabel,
  isProcessing,
  isTerminal,
  summarizeContent,
} from '../composables/useDocumentStatus'

const route = useRoute()
const loading = ref(false)
const retrying = ref(false)
const doc = ref(null)
const { start: startPolling, stop: stopPolling } = useDocumentPolling()

async function loadDetail() {
  loading.value = true
  try {
    const id = route.params.id
    const res = await getDocument(id)
    doc.value = res.data ?? null
  } finally {
    loading.value = false
  }
}

function setupPolling() {
  const id = Number(route.params.id)
  if (!id || !doc.value) return

  stopPolling(id)

  if (isTerminal(doc.value.parseStatus)) return

  startPolling(
    id,
    (status) => {
      if (!doc.value) return
      doc.value.parseStatus = status.parseStatus
      doc.value.chunkCount = status.chunkCount
      doc.value.errorMsg = status.errorMsg
    },
    async (status) => {
      if (status.parseStatus === 'completed') {
        await loadDetail()
      }
    },
  )
}

watch(
  () => route.params.id,
  async () => {
    await loadDetail()
    setupPolling()
  },
)

onMounted(async () => {
  await loadDetail()
  setupPolling()
})

onUnmounted(() => {
  const id = Number(route.params.id)
  if (id) stopPolling(id)
})

async function onReprocess() {
  if (!doc.value) return
  retrying.value = true
  try {
    await reprocessDocument(doc.value.id)
    doc.value.parseStatus = 'pending'
    doc.value.errorMsg = null
    doc.value.chunkCount = 0
    setupPolling()
  } catch (e) {
    alert(e?.response?.data?.message || e?.message || '重试失败')
  } finally {
    retrying.value = false
  }
}

function formatTime(iso) {
  if (!iso) return '-'
  return iso.toString().replace('T', ' ').slice(0, 19)
}

function formatBytes(bytes) {
  if (bytes == null) return '-'
  const n = Number(bytes)
  if (Number.isNaN(n)) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`
}
</script>

<style scoped>
.page-body {
  padding: 20px 28px 32px;
}

.doc-header {
  margin-bottom: 16px;
  padding: 16px 20px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.doc-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 700;
  color: var(--slate);
  word-break: break-all;
}

.doc-sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-muted);
}

.dot {
  opacity: 0.5;
}

.doc-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.doc-left {
  padding: 20px;
  border-right: 1px solid var(--border);
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}

.doc-right {
  padding: 20px;
  background: #fafbfc;
}

.section-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 14px;
  color: var(--slate);
}

.chunk-card {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.6;
  border: 1px solid var(--border);
  background: #fff;
}

.chunk-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.chunk-title {
  font-weight: 600;
  font-size: 11px;
  color: var(--slate);
}

.chunk-tokens {
  font-size: 11px;
  color: var(--text-muted);
}

.chunk-text {
  color: var(--gray);
  white-space: pre-wrap;
  word-break: break-word;
}

.meta-list {
  margin-bottom: 16px;
}

.meta-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
  font-size: 12px;
}

.meta-key {
  color: var(--text-muted);
  width: 80px;
  flex-shrink: 0;
}

.meta-val {
  font-weight: 500;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 700;
  border: 1px solid transparent;
}

.badge-green {
  background: #dcfce7;
  color: #166534;
  border-color: rgba(22, 101, 52, 0.2);
}

.badge-amber {
  background: #fef3c7;
  color: #92400e;
  border-color: rgba(146, 64, 14, 0.2);
}

.badge-red {
  background: #fee2e2;
  color: #991b1b;
  border-color: rgba(153, 27, 27, 0.2);
}

.badge-gray {
  background: rgba(148, 163, 184, 0.18);
  color: #64748b;
  border-color: rgba(148, 163, 184, 0.25);
}

.status-icon.ok {
  color: #16a34a;
}

.status-icon.spin {
  display: inline-block;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.search-action {
  padding: 10px;
  border: 1px solid var(--blue);
  border-radius: var(--radius-sm);
  text-align: center;
  color: var(--blue);
  font-size: 12px;
  cursor: pointer;
  font-weight: 500;
  transition: background 0.15s;
}

.search-action:hover {
  background: #eff6ff;
}

.processing-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 280px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 40px 20px;
}

.processing-spinner {
  font-size: 36px;
  color: #d97706;
  animation: spin 1s linear infinite;
}

.processing-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--slate);
}

.processing-status {
  font-size: 13px;
  color: var(--text-muted);
}

.processing-error {
  margin: 0;
  max-width: 480px;
  text-align: center;
  font-size: 12px;
  color: #b91c1c;
  line-height: 1.5;
  word-break: break-word;
}

.status-icon.fail {
  font-size: 28px;
  color: #dc2626;
  font-weight: 700;
}

.btn-retry {
  margin-top: 8px;
  padding: 8px 24px;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  background: var(--blue);
  color: #fff;
}

.btn-retry:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}


/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .doc-layout {
    grid-template-columns: 1fr;
  }

  .doc-left {
    border-right: none;
    border-bottom: 1px solid var(--border);
    max-height: none;
  }

  .doc-header {
    padding: 12px 16px;
  }

  .doc-title {
    font-size: 16px;
  }
}
</style>
