<template>
  <div>
    <div class="page-body">
      <div class="page-head">
        <div class="head-actions">
          <button class="btn-ghost" type="button" @click="$router.push('/knowledge')">← 返回知识库</button>
          <button class="btn-ghost" type="button" :disabled="loading" @click="loadDocs(page)">刷新</button>
        </div>
        <PageBreadcrumb class="page-crumb" :items="breadcrumbItems" />
      </div>

      <div class="summary-grid">
        <div>
          <span>文档</span>
          <strong>{{ total }}</strong>
        </div>
        <div>
          <span>Chunk</span>
          <strong>{{ kb?.chunkCount ?? 0 }}</strong>
        </div>
        <div>
          <span>状态</span>
          <strong>{{ statusLabel(kb?.status) }}</strong>
        </div>
      </div>

      <div class="docs-panel">
        <div v-if="loading && !docs.length" class="empty">加载文档中...</div>
        <div v-else-if="!docs.length" class="empty">暂无文档，请从知识库管理页上传</div>
        <div v-else class="doc-list">
          <article v-for="doc in docs" :key="doc.id" class="doc-row">
            <div class="doc-type">{{ fileTypeLabel(doc.filename) }}</div>
            <div class="doc-main">
              <h3>{{ doc.filename }}</h3>
              <div class="doc-meta">
                {{ formatBytes(doc.fileSize) }} · v{{ doc.version ?? 1 }} · {{ doc.chunkCount ?? 0 }} chunks · {{ formatTime(doc.createdAt) }}
              </div>
              <div v-if="doc.parseStatus === 'failed' && doc.errorMsg" class="doc-error">
                {{ doc.errorMsg }}
              </div>
            </div>
            <DocumentStatusBadge
              class="doc-status-col"
              :parse-status="doc.parseStatus"
              :error-msg="doc.errorMsg"
            />
            <div class="doc-actions">
              <button class="link-btn" @click="goDoc(doc.id)">详情</button>
              <button class="link-btn" @click="onDownloadDoc(doc)">下载</button>
              <button v-if="doc.parseStatus === 'failed'" class="link-btn" @click="onReprocessDoc(doc)">重试</button>
              <button
                class="link-btn danger"
                :disabled="!deleteEnabled"
                :title="deleteEnabled ? '删除文档' : '演示环境已禁用删除'"
                @click="onDeleteDoc(doc)"
              >
                删除
              </button>
            </div>
          </article>
        </div>

        <div class="pager">
          <span>共 {{ total }} 条</span>
          <button class="btn-ghost btn-ghost-small" :disabled="page <= 1 || loading" @click="loadDocs(page - 1)">上一页</button>
          <span>第 {{ page }} / {{ totalPages }} 页</span>
          <button class="btn-ghost btn-ghost-small" :disabled="page >= totalPages || loading" @click="loadDocs(page + 1)">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageBreadcrumb from '../components/PageBreadcrumb.vue'
import DocumentStatusBadge from '../components/DocumentStatusBadge.vue'
import { KB_DOCUMENT_DELETE_ENABLED } from '../config/uiPolicy'
import { listKb } from '../api/kb'
import { deleteDocument, downloadDocument, listDocuments, reprocessDocument } from '../api/document'

const deleteEnabled = KB_DOCUMENT_DELETE_ENABLED
import { documentDetailRoute } from '../composables/useDocumentNav'

const route = useRoute()
const router = useRouter()
const kb = ref(null)
const docs = ref([])
const page = ref(1)
const size = 20
const total = ref(0)
const loading = ref(false)

const kbId = computed(() => Number(route.params.kbId))
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const breadcrumbItems = computed(() => [
  { label: '知识库管理', to: '/knowledge' },
  { label: kb.value?.name || '…' },
  { label: '文档列表', current: true },
])

async function loadKb() {
  const res = await listKb()
  kb.value = (res.data ?? []).find((item) => item.id === kbId.value) || null
}

async function loadDocs(nextPage = 1) {
  loading.value = true
  try {
    const res = await listDocuments(kbId.value, nextPage, size)
    docs.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
    page.value = res.data?.page ?? nextPage
  } finally {
    loading.value = false
  }
}

function goDoc(id) {
  router.push(documentDetailRoute(id, { from: 'documents', kbId: kbId.value }))
}

async function onReprocessDoc(doc) {
  await reprocessDocument(doc.id)
  await loadDocs(page.value)
  await loadKb()
}

async function onDeleteDoc(doc) {
  if (!deleteEnabled) return
  if (!confirm(`确定删除文档「${doc.filename}」？`)) return
  await deleteDocument(doc.id)
  const nextPage = docs.value.length === 1 && page.value > 1 ? page.value - 1 : page.value
  await loadDocs(nextPage)
  await loadKb()
}

async function onDownloadDoc(doc) {
  const res = await downloadDocument(doc.id)
  const url = window.URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = doc.filename || `document-${doc.id}`
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.URL.revokeObjectURL(url)
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

function statusLabel(status) {
  const map = { active: '可用', deleted: '已删除', rebuilding: '重建中' }
  return map[status] || status || '-'
}

function fileTypeLabel(filename) {
  const ext = (filename || '').split('.').pop()?.toUpperCase()
  if (!ext || ext === filename) return 'DOC'
  if (ext === 'MARKDOWN') return 'MD'
  return ext.slice(0, 4)
}

onMounted(async () => {
  await loadKb()
  await loadDocs(1)
})
</script>

<style scoped>
.page-body {
  padding: 20px 28px 32px;
}

.page-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
  min-width: 0;
}

.head-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.page-crumb {
  width: 100%;
  min-width: 0;
}

.btn-ghost {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}

.btn-ghost:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-ghost-small {
  padding: 6px 10px;
  font-size: 12px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.summary-grid > div,
.docs-panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.summary-grid > div {
  padding: 12px 14px;
}

.summary-grid span {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
}

.summary-grid strong {
  display: block;
  margin-top: 2px;
  color: var(--slate);
  font-size: 17px;
}

.docs-panel {
  padding: 14px;
}

.doc-list {
  display: grid;
  gap: 10px;
}

.doc-row {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px;
}

.doc-type {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #f1f5f9;
  color: var(--slate);
  font-size: 11px;
  font-weight: 900;
}

.doc-main {
  min-width: 0;
}

.doc-main h3 {
  margin: 0;
  color: var(--slate);
  font-size: 13px;
  line-height: 1.45;
  word-break: break-word;
}

.doc-meta {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 11px;
}

.doc-error {
  margin-top: 5px;
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.5;
}

.doc-status-col {
  justify-self: center;
}

.doc-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
}

.link-btn {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--blue);
  padding: 6px 9px;
  font-size: 12px;
  cursor: pointer;
}

.link-btn.danger {
  color: var(--red);
  border-color: #fecaca;
}

.empty {
  padding: 36px 12px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 768px) {
  .page-body {
    padding: 14px 12px 24px;
  }

  .head-actions {
    width: 100%;
  }

  .head-actions .btn-ghost {
    flex: 1 1 auto;
    min-height: 40px;
    padding: 8px 12px;
    font-size: 12px;
  }

  .page-head :deep(.page-breadcrumb) {
    font-size: 11px;
    gap: 4px;
  }

  .summary-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }

  .summary-grid > div {
    padding: 10px;
  }

  .summary-grid strong {
    font-size: 15px;
  }

  .docs-panel {
    padding: 10px;
  }

  .doc-row {
    grid-template-columns: 40px minmax(0, 1fr);
    grid-template-areas:
      'type main'
      '. status'
      '. actions';
    gap: 8px 10px;
    padding: 10px;
  }

  .doc-type {
    grid-area: type;
    width: 36px;
    height: 36px;
    font-size: 10px;
  }

  .doc-main {
    grid-area: main;
  }

  .doc-status-col {
    grid-area: status;
    justify-self: start;
  }

  .doc-actions {
    grid-area: actions;
    width: 100%;
    justify-content: flex-start;
  }

  .link-btn {
    padding: 7px 10px;
    font-size: 11px;
  }

  .pager {
    flex-wrap: wrap;
    justify-content: flex-start;
    gap: 8px;
  }

  .pager .btn-ghost-small {
    min-height: 36px;
  }
}
</style>
