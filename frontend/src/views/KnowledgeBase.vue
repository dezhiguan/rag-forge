<template>
  <div>
    <div class="page-body">
      <div class="top-toolbar">
        <div class="toolbar-left">
          <button class="btn-primary" @click="openCreate">+ 创建知识库</button>
          <button class="btn-ghost" :disabled="loadingKb" @click="loadKbs">刷新</button>
        </div>
      </div>

      <div v-if="loadingKb" class="state-hint">加载中…</div>
      <div v-else-if="!kbList.length" class="state-hint">暂无知识库，点击上方按钮创建</div>

      <div v-else class="content">
        <div class="upload-layout">
          <div class="upload-settings">
            <label class="select-label">
              上传到知识库
              <select v-model="uploadKbId" class="select">
                <option v-for="kb in kbList" :key="kb.id" :value="kb.id">
                  {{ kb.name }}
                </option>
              </select>
            </label>
            <div v-if="uploadProcessing" class="processing-hint">处理中…</div>
          </div>

          <div
            class="upload-zone"
            @dragover.prevent="isDragOver = true"
            @dragleave.prevent="isDragOver = false"
            @drop.prevent="onDrop"
            :class="{ 'upload-zone-drag': isDragOver }"
            @click="onPickFile"
          >
            <input
              ref="fileInputRef"
              class="hidden-file"
              type="file"
              multiple
              accept=".pdf,.doc,.docx,.md,.markdown,.html,.htm,application/pdf"
              @change="onFileChange"
            />
            <div class="upload-icon">📤</div>
            <div class="upload-text">
              拖拽文件上传 · 支持 PDF / Markdown / Word / HTML
            </div>
            <div class="upload-hint">单文件最大 50MB · 支持批量上传</div>
          </div>
        </div>

        <div class="pipeline" aria-label="processing pipeline">
          <div class="pipe-item">
            <div class="pipe-icon">📥</div>
            <div class="pipe-name">解析</div>
          </div>
          <span class="pipe-arrow">→</span>
          <div class="pipe-item">
            <div class="pipe-icon">✂️</div>
            <div class="pipe-name">分块</div>
            <div class="pipe-desc">512 tokens</div>
          </div>
          <span class="pipe-arrow">→</span>
          <div class="pipe-item">
            <div class="pipe-icon">🧮</div>
            <div class="pipe-name">向量化</div>
          </div>
          <span class="pipe-arrow">→</span>
          <div class="pipe-item">
            <div class="pipe-icon">📇</div>
            <div class="pipe-name">BM25</div>
          </div>
          <span class="pipe-arrow">→</span>
          <div class="pipe-item">
            <div class="pipe-icon">✅</div>
            <div class="pipe-name">可用</div>
          </div>
        </div>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 36%;">知识库</th>
                <th>文档</th>
                <th>Chunk</th>
                <th>状态</th>
                <th>创建时间</th>
                <th style="width: 110px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="kb in kbList" :key="kb.id">
                <tr class="kb-row" @click="toggleKb(kb.id)">
                  <td>
                    <div class="kb-cell">
                      <span class="expander">{{ expandedKbId === kb.id ? '▾' : '▸' }}</span>
                      <div class="kb-title">
                        <strong>{{ kb.name }}</strong>
                        <div v-if="kb.description" class="desc">{{ kb.description }}</div>
                      </div>
                    </div>
                  </td>
                  <td>{{ kb.docCount ?? 0 }}</td>
                  <td>{{ kb.chunkCount ?? 0 }}</td>
                  <td>
                    <span class="badge" :class="statusClass(kb.status)">
                      {{ statusLabel(kb.status) }}
                    </span>
                  </td>
                  <td>{{ formatTime(kb.createdAt) }}</td>
                  <td>
                    <span class="link-action danger" @click.stop="onDeleteKb(kb)">
                      删除
                    </span>
                  </td>
                </tr>

                <tr v-if="expandedKbId === kb.id" class="docs-row">
                  <td colspan="6">
                    <div class="docs-panel">
                      <div class="docs-head">
                        <div class="docs-title">文档列表</div>
                        <button
                          class="btn-ghost btn-ghost-small"
                          :disabled="docsLoading[kb.id]"
                          @click.stop="loadDocs(kb.id)"
                        >
                          刷新
                        </button>
                      </div>

                      <table class="docs-table">
                        <thead>
                          <tr>
                            <th>文件名</th>
                            <th>大小</th>
                            <th>状态</th>
                            <th>Chunk</th>
                            <th>上传时间</th>
                            <th style="width: 180px;">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-if="(docsMap[kb.id]?.list || []).length === 0">
                            <td colspan="6" class="empty-hint">暂无文档</td>
                          </tr>
                          <tr v-for="doc in docsMap[kb.id]?.list || []" :key="doc.id">
                            <td class="doc-filename">
                              {{ doc.filename }}
                              <span class="doc-version">v{{ doc.version ?? 1 }}</span>
                            </td>
                            <td>{{ formatBytes(doc.fileSize) }}</td>
                            <td>
                              <div
                                class="doc-status-cell"
                                :title="doc.parseStatus === 'failed' ? doc.errorMsg || '处理失败' : undefined"
                              >
                                <span
                                  v-if="isProcessing(doc.parseStatus)"
                                  class="status-icon spin"
                                  aria-hidden="true"
                                >⟳</span>
                                <span
                                  v-else-if="doc.parseStatus === 'completed'"
                                  class="status-icon ok"
                                  aria-hidden="true"
                                >✓</span>
                                <span
                                  v-else-if="doc.parseStatus === 'failed'"
                                  class="status-icon fail"
                                  aria-hidden="true"
                                >✗</span>
                                <span class="badge" :class="docStatusClass(doc.parseStatus)">
                                  {{ docStatusLabel(doc.parseStatus) }}
                                </span>
                              </div>
                            </td>
                            <td>{{ doc.chunkCount ?? 0 }}</td>
                            <td>{{ formatTime(doc.createdAt) }}</td>
                            <td>
                              <span class="link-action" @click.stop="goDoc(doc.id)">详情</span>
                              <span class="link-action" @click.stop="onDownloadDoc(doc)">下载</span>
                              <span
                                v-if="doc.parseStatus === 'failed'"
                                class="link-action"
                                @click.stop="onReprocessDoc(doc)"
                              >
                                重试
                              </span>
                              <span
                                class="link-action danger"
                                @click.stop="onDeleteDoc(doc)"
                              >
                                删除
                              </span>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
        <div class="modal">
          <h3 class="modal-title">创建知识库</h3>
          <label class="field">
            <span>名称 *</span>
            <input v-model="kbForm.name" type="text" placeholder="例如：产品文档库" />
          </label>
          <label class="field">
            <span>描述</span>
            <textarea v-model="kbForm.description" rows="3" placeholder="可选" />
          </label>
          <div class="modal-actions">
            <button class="btn-ghost" @click="showCreate = false">取消</button>
            <button class="btn-primary" :disabled="submittingKb" @click="onCreateKb">
              确定
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createKb, deleteKb, listKb } from '../api/kb'
import {
  deleteDocument,
  downloadDocument,
  listDocuments,
  reprocessDocument,
  replaceDocument,
  uploadDocument,
} from '../api/document'
import { useDocumentPolling } from '../composables/useDocumentPolling'
import {
  docStatusClass,
  docStatusLabel,
  isProcessing,
} from '../composables/useDocumentStatus'

const router = useRouter()
const { start: startDocPolling, stop: stopDocPolling } = useDocumentPolling()

const kbList = ref([])
const loadingKb = ref(false)
const expandedKbId = ref(null)

const docsMap = reactive({})
const docsLoading = reactive({})

const uploadKbId = ref(null)
const uploadProcessing = ref(false)
const isDragOver = ref(false)
const fileInputRef = ref(null)

const showCreate = ref(false)
const submittingKb = ref(false)
const kbForm = ref({ name: '', description: '' })

async function loadKbs() {
  loadingKb.value = true
  try {
    const res = await listKb()
    kbList.value = res.data ?? []
    if (!uploadKbId.value && kbList.value.length) {
      uploadKbId.value = kbList.value[0].id
    }
  } finally {
    loadingKb.value = false
  }
}

function applyStatusToDoc(kbId, docId, status) {
  const list = docsMap[kbId]?.list
  if (!list) return
  const doc = list.find((d) => d.id === docId)
  if (!doc) return
  doc.parseStatus = status.parseStatus
  doc.chunkCount = status.chunkCount
  doc.errorMsg = status.errorMsg
}

function watchProcessingDocs(kbId) {
  const list = docsMap[kbId]?.list ?? []
  for (const doc of list) {
    if (!isProcessing(doc.parseStatus)) continue
    startDocPolling(
      doc.id,
      (status) => applyStatusToDoc(kbId, doc.id, status),
      async () => {
        await loadDocs(kbId)
        await loadKbs()
      },
    )
  }
}

async function loadDocs(kbId) {
  docsLoading[kbId] = true
  try {
    const res = await listDocuments(kbId, 1, 20)
    docsMap[kbId] = {
      list: res.data?.list ?? [],
    }
    watchProcessingDocs(kbId)
  } finally {
    docsLoading[kbId] = false
  }
}

function beginPollingDoc(docId, kbId) {
  if (!docId || !kbId) return
  startDocPolling(
    docId,
    (status) => applyStatusToDoc(kbId, docId, status),
    async () => {
      await loadDocs(kbId)
      await loadKbs()
    },
  )
}

async function toggleKb(kbId) {
  expandedKbId.value = expandedKbId.value === kbId ? null : kbId
  if (expandedKbId.value === kbId && !(docsMap[kbId]?.list?.length > 0)) {
    // 空列表也允许展开，但这里先拉一次
    await loadDocs(kbId)
  }
}

function openCreate() {
  kbForm.value = { name: '', description: '' }
  showCreate.value = true
}

async function onCreateKb() {
  if (!kbForm.value.name?.trim()) {
    alert('请填写知识库名称')
    return
  }
  submittingKb.value = true
  try {
    await createKb({
      name: kbForm.value.name.trim(),
      description: kbForm.value.description || undefined,
    })
    showCreate.value = false
    await loadKbs()
  } finally {
    submittingKb.value = false
  }
}

async function onDeleteKb(kb) {
  if (!confirm(`确定删除知识库「${kb.name}」？`)) return
  await deleteKb(kb.id)
  await loadKbs()
  if (expandedKbId.value === kb.id) expandedKbId.value = null
}

function onPickFile() {
  if (fileInputRef.value) fileInputRef.value.click()
}

async function handleFiles(files) {
  if (!uploadKbId.value) {
    alert('请先选择上传到哪个知识库')
    return
  }
  if (!files || files.length === 0) return

  uploadProcessing.value = true
  try {
    for (const file of files) {
      const res = await uploadDocument(uploadKbId.value, file)
      const payload = res.data
      if (payload?.exists && payload?.existingDocument) {
        const version = payload.existingDocument.version ?? 1
        const confirmed = confirm(`该文件已存在（v${version}），是否覆盖更新？`)
        if (confirmed) {
          const replaceRes = await replaceDocument(
            uploadKbId.value,
            payload.existingDocument.id,
            file,
          )
          const replaced = replaceRes.data
          if (replaced?.id) {
            beginPollingDoc(replaced.id, uploadKbId.value)
          }
        }
      } else {
        const docId = payload?.documentId ?? payload?.document?.id
        if (docId) {
          beginPollingDoc(docId, uploadKbId.value)
        }
      }
    }
    await loadKbs()
    if (expandedKbId.value === uploadKbId.value) {
      await loadDocs(uploadKbId.value)
    } else if (uploadKbId.value) {
      expandedKbId.value = uploadKbId.value
      await loadDocs(uploadKbId.value)
    }
  } finally {
    uploadProcessing.value = false
  }
}

function onFileChange(e) {
  const files = e.target.files ? Array.from(e.target.files) : []
  handleFiles(files)
  e.target.value = ''
}

async function onDrop(e) {
  isDragOver.value = false
  const files = e.dataTransfer?.files ? Array.from(e.dataTransfer.files) : []
  handleFiles(files)
}

function goDoc(id) {
  router.push(`/document/${id}`)
}

async function onReprocessDoc(doc) {
  try {
    await reprocessDocument(doc.id)
    applyStatusToDoc(doc.kbId, doc.id, {
      parseStatus: 'pending',
      chunkCount: 0,
      errorMsg: null,
    })
    beginPollingDoc(doc.id, doc.kbId)
  } catch (e) {
    alert(e?.response?.data?.message || e?.message || '重试失败')
  }
}

async function onDeleteDoc(doc) {
  if (!confirm(`确定删除文档「${doc.filename}」？`)) return
  stopDocPolling(doc.id)
  await deleteDocument(doc.id)
  // 删除后刷新知识库计数 + 文档列表
  await loadKbs()
  if (expandedKbId.value) {
    await loadDocs(expandedKbId.value)
  }
}

async function onDownloadDoc(doc) {
  const res = await downloadDocument(doc.id)
  const blob = res.data
  const url = window.URL.createObjectURL(blob)
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

function statusClass(status) {
  if (status === 'active') return 'badge-green'
  if (status === 'rebuilding') return 'badge-amber'
  return 'badge-gray'
}

onMounted(async () => {
  await loadKbs()
  if (kbList.value.length) {
    expandedKbId.value = kbList.value[0].id
    await loadDocs(kbList.value[0].id)
  }
})
</script>

<style scoped>
.page-body {
  padding: 20px 28px 32px;
}

.top-toolbar {
  margin-bottom: 12px;
}

.toolbar-left {
  display: flex;
  gap: 10px;
}

.btn-primary {
  background: var(--blue);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }

.btn-ghost {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}
.btn-ghost:disabled { opacity: 0.5; }

.btn-ghost-small {
  padding: 6px 10px;
  font-size: 12px;
}

.state-hint {
  text-align: center;
  color: var(--text-muted);
  padding: 48px 0;
  font-size: 14px;
}

.content {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.upload-layout {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.upload-settings {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.select-label {
  font-size: 12px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 10px;
}

.select {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  background: #fff;
  color: var(--text);
}

.processing-hint {
  font-size: 12px;
  color: var(--blue);
  font-weight: 600;
}

.upload-zone {
  border: 2px dashed #e2e8f0;
  border-radius: 10px;
  padding: 20px;
  text-align: center;
  background: #fafbfc;
  cursor: pointer;
  transition: all 0.15s ease;
}

.upload-zone-drag {
  border-color: var(--blue);
  background: #eff6ff;
}

.upload-icon {
  font-size: 28px;
  margin-bottom: 6px;
}
.upload-text {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}
.upload-hint {
  font-size: 10px;
  color: var(--text-muted);
}
.hidden-file { display: none; }

.pipeline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px;
  font-size: 10px;
}

.pipe-item {
  flex: 1;
  background: #f1f5f9;
  border: 2px solid transparent;
  border-radius: 10px;
  padding: 12px 10px;
  text-align: center;
}

.pipe-icon { font-size: 16px; }
.pipe-name { font-weight: 700; font-size: 11px; margin-top: 4px; }
.pipe-desc { font-size: 9px; color: var(--text-muted); margin-top: 2px; }
.pipe-arrow { color: var(--border); font-size: 16px; }

.table-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table thead th {
  text-align: left;
  padding: 12px 16px;
  background: var(--light);
  color: var(--slate);
  font-weight: 700;
  font-size: 11px;
  text-transform: uppercase;
  border-bottom: 1px solid var(--border);
}

.data-table tbody td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  color: var(--gray);
  vertical-align: top;
}

.kb-row {
  cursor: pointer;
  background: #fff;
}
.kb-row:hover {
  background: #f8fafc;
}

.kb-cell {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.expander { font-size: 16px; color: var(--text-muted); line-height: 1.4; }
.kb-title strong { font-size: 13px; }
.desc { font-size: 11px; color: var(--text-muted); margin-top: 2px; font-weight: 500; }

.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid transparent;
}
.badge-green { background: #dcfce7; color: #166534; border-color: rgba(22,101,52,0.2); }
.badge-amber { background: #fef3c7; color: #92400e; border-color: rgba(146,64,14,0.2); }

.doc-status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
}

.status-icon {
  font-size: 14px;
  line-height: 1;
  flex-shrink: 0;
}

.status-icon.ok {
  color: #16a34a;
  font-weight: 700;
}

.status-icon.fail {
  color: #dc2626;
  font-weight: 700;
}

.status-icon.spin {
  color: #d97706;
  display: inline-block;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.badge-gray { background: #f1f5f9; color: #64748b; border-color: rgba(148,163,184,0.35); }
.badge-red { background: #fee2e2; color: #991b1b; border-color: rgba(239,68,68,0.25); }

.link-action { cursor: pointer; font-size: 12px; color: var(--blue); margin-right: 10px; }
.link-action.danger { color: var(--red); margin-right: 0; }

.docs-row td { padding: 0; }
.docs-panel {
  padding: 16px;
  background: #fbfdff;
  border-top: 1px solid var(--border);
}

.docs-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.docs-title { font-size: 13px; color: var(--slate); font-weight: 800; }

.docs-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.docs-table thead th {
  text-align: left;
  padding: 10px 12px;
  color: var(--slate);
  font-weight: 700;
  font-size: 11px;
  text-transform: uppercase;
  border-bottom: 1px solid var(--border);
}
.docs-table tbody td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  color: var(--gray);
}
.empty-hint { color: var(--text-muted); text-align: center; padding: 20px 12px; }
.doc-filename { max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-version {
  margin-left: 6px;
  font-size: 11px;
  color: var(--text-muted);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: min(420px, 92vw);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.15);
}
.modal-title { font-size: 16px; margin-bottom: 16px; }
.field { display: block; margin-bottom: 14px; }
.field span { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
.field input, .field textarea {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
}
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
</style>
