<template>
  <div>
    <div class="page-body">
      <div class="detail-nav">
        <button class="btn-ghost" type="button" @click="onBack">← 返回上一页</button>
      </div>

      <PageBreadcrumb :items="breadcrumbItems" />

      <div v-if="loading && !doc" class="state-hint">
        <div class="state-icon">⏳</div>
        <div class="state-title">加载中...</div>
      </div>

      <template v-else-if="doc">
        <header class="doc-header">
          <div class="doc-header-main">
            <h1 class="doc-title">{{ doc.filename }}</h1>
            <div class="doc-header-actions">
              <button
                type="button"
                class="link-btn danger"
                :disabled="!deleteEnabled"
                :title="deleteEnabled ? '删除文档' : '演示环境已禁用删除'"
                @click="onDeleteDoc"
              >
                删除
              </button>
            </div>
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
          <div class="doc-left" @scroll="onChunksScroll">
            <div class="section-title">
              📄 Chunks（{{ chunks.length }} / {{ chunkTotal }}）
            </div>
            <div v-if="!chunks.length && loadingChunks" class="state-hint" style="padding:24px 0">
              <div class="state-desc">正在加载分块数据...</div>
            </div>
            <div v-else-if="!chunks.length" class="state-hint" style="padding:24px 0">
              <div class="state-desc">文档处理完成后将显示分块数据</div>
            </div>
            <div v-else>
              <div v-for="c in chunks" :key="c.chunkIndex" class="chunk-card">
                <div class="chunk-head">
                  <span class="chunk-title">#{{ c.chunkIndex }}</span>
                  <span class="chunk-tokens">{{ c.tokenCount ?? 0 }} tokens</span>
                </div>
                <div class="chunk-text" :title="c.content">{{ summarizeContent(c.content) }}</div>
              </div>
              <div class="chunk-load-state">
                <button v-if="chunkError" class="chunk-load-btn" @click="loadChunksPage(chunkPage)">
                  加载失败，点击重试
                </button>
                <span v-else-if="loadingChunks">加载中...</span>
                <span v-else-if="!hasMoreChunks">已加载全部</span>
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
                  <span class="badge" :class="docStatusClass(doc.parseStatus)">
                    {{ docStatusLabel(doc.parseStatus) }}
                  </span>
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
        <div class="state-desc">请检查链接或从知识库管理重新进入</div>
        <button class="btn-ghost state-back" type="button" @click="$router.push('/knowledge')">
          返回知识库管理
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageBreadcrumb from '../components/PageBreadcrumb.vue'
import { KB_DOCUMENT_DELETE_ENABLED } from '../config/uiPolicy'
import { deleteDocument, getDocument, listDocumentChunks, reprocessDocument } from '../api/document'

const deleteEnabled = KB_DOCUMENT_DELETE_ENABLED
import { navigateBackFromDocument } from '../composables/useDocumentNav'
import { useDocumentPolling } from '../composables/useDocumentPolling'
import {
  docStatusClass,
  docStatusLabel,
  isProcessing,
  isTerminal,
  summarizeContent,
} from '../composables/useDocumentStatus'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const retrying = ref(false)
const doc = ref(null)
const chunks = ref([])
const chunkPage = ref(1)
const chunkSize = 20
const chunkTotal = ref(0)
const loadingChunks = ref(false)
const chunkError = ref(false)
const { start: startPolling, stop: stopPolling } = useDocumentPolling()

const hasMoreChunks = computed(() => chunks.value.length < chunkTotal.value)

const breadcrumbItems = computed(() => {
  if (loading.value && !doc.value) {
    return [
      { label: '知识库管理', to: '/knowledge' },
      { label: '…', current: true },
      { label: '…', current: true },
    ]
  }
  if (!doc.value) {
    return [
      { label: '知识库管理', to: '/knowledge' },
      { label: '文档不存在', current: true },
    ]
  }
  const kbId = doc.value.kbId
  const kbName = doc.value.kbName || '知识库'
  return [
    { label: '知识库管理', to: '/knowledge' },
    ...(kbId ? [{ label: kbName, to: `/knowledge/${kbId}/documents` }] : [{ label: kbName, current: true }]),
    { label: doc.value.filename, current: true },
  ]
})

function onBack() {
  navigateBackFromDocument(router, route, doc.value?.kbId)
}

async function loadDetail() {
  loading.value = true
  try {
    const id = route.params.id
    const res = await getDocument(id)
    doc.value = res.data ?? null
    resetChunks()
    if (doc.value?.parseStatus === 'completed') {
      await loadChunksPage(1)
    }
  } finally {
    loading.value = false
  }
}

function resetChunks() {
  chunks.value = []
  chunkPage.value = 1
  chunkTotal.value = doc.value?.chunkCount ?? 0
  chunkError.value = false
}

async function loadChunksPage(page = chunkPage.value) {
  if (!doc.value || loadingChunks.value) return
  if (page > 1 && !hasMoreChunks.value) return
  loadingChunks.value = true
  chunkError.value = false
  try {
    const res = await listDocumentChunks(doc.value.id, page, chunkSize)
    const data = res.data ?? {}
    const list = data.list ?? []
    chunkTotal.value = data.total ?? chunkTotal.value
    chunks.value = page === 1 ? list : [...chunks.value, ...list]
    chunkPage.value = page + 1
  } catch {
    chunkError.value = true
  } finally {
    loadingChunks.value = false
  }
}

function onChunksScroll(event) {
  if (loadingChunks.value || chunkError.value || !hasMoreChunks.value) return
  const el = event.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 80) {
    loadChunksPage(chunkPage.value)
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

async function onDeleteDoc() {
  if (!deleteEnabled || !doc.value?.id) return
  if (!confirm(`确定删除文档「${doc.value.filename}」？`)) return
  await deleteDocument(doc.value.id)
  onBack()
}

async function onReprocess() {
  if (!doc.value) return
  retrying.value = true
  try {
    await reprocessDocument(doc.value.id)
    doc.value.parseStatus = 'pending'
    doc.value.errorMsg = null
    doc.value.chunkCount = 0
    resetChunks()
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

.detail-nav {
  margin-bottom: 10px;
}

.page-body :deep(.page-breadcrumb) {
  margin-bottom: 14px;
}

.btn-ghost {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}

.state-back {
  margin-top: 12px;
}

.doc-header {
  margin-bottom: 16px;
  padding: 16px 20px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.doc-header-main {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.doc-header-actions {
  flex-shrink: 0;
}

.link-btn {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--blue);
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
}

.link-btn.danger {
  color: var(--red);
  border-color: #fecaca;
}

.link-btn:disabled,
.link-btn.danger:disabled {
  color: var(--text-muted);
  border-color: var(--border);
  background: #f8fafc;
  opacity: 0.65;
  cursor: not-allowed;
}

.doc-title {
  margin: 0 0 8px;
  flex: 1;
  min-width: 0;
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

.chunk-load-state {
  padding: 10px 0 2px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
}

.chunk-load-btn {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--blue);
  padding: 8px 12px;
  font-size: 12px;
  cursor: pointer;
}

.chunk-load-btn:hover {
  background: #eff6ff;
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
    padding: 14px;
  }

  .doc-right {
    padding: 14px;
  }

  .doc-header {
    padding: 12px 16px;
  }

  .doc-title {
    font-size: 16px;
    line-height: 1.45;
  }

  .doc-sub {
    gap: 6px;
    font-size: 12px;
  }

  .dot {
    display: none;
  }

  .doc-sub > span {
    max-width: 100%;
  }

  .chunk-card {
    padding: 10px;
  }

  .chunk-head {
    align-items: flex-start;
    gap: 8px;
  }

  .chunk-tokens {
    flex-shrink: 0;
  }

  .chunk-text {
    max-height: 180px;
    overflow-y: auto;
    padding-right: 2px;
  }

  .meta-row {
    display: grid;
    grid-template-columns: 72px minmax(0, 1fr);
    gap: 8px;
  }

  .meta-key {
    width: auto;
  }

  .meta-val {
    min-width: 0;
    word-break: break-word;
  }

  .search-action,
  .btn-retry {
    min-height: 42px;
  }

  .processing-panel {
    min-height: 220px;
    padding: 28px 16px;
  }
}
</style>
