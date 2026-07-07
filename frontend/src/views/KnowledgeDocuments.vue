<template>
  <div>
    <div class="page-body">
      <div class="page-head">
        <div class="head-actions">
          <button class="btn btn-secondary" type="button" @click="$router.push('/knowledge')">← 返回知识库</button>
          <button class="btn btn-secondary" type="button" :disabled="loading" @click="loadDocs(page)">刷新</button>
        </div>
        <PageBreadcrumb class="page-crumb" :items="breadcrumbItems" />
      </div>

      <div v-if="kbInaccessible" class="empty" style="padding:48px 0;text-align:center">
        <div style="font-size:32px">🔒</div>
        <div style="margin-top:8px;font-weight:600">无法访问该知识库</div>
        <div style="margin-top:4px;color:#8a94a6">知识库不存在，或不属于当前组织、你没有访问权限。请切换到对应组织或返回知识库管理页。</div>
        <button class="btn btn-secondary" type="button" style="margin-top:16px" @click="$router.push('/knowledge')">← 返回知识库</button>
      </div>

      <div v-if="!kbInaccessible" class="summary-grid">
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

      <div v-if="!kbInaccessible" class="docs-panel">
        <div class="doc-toolbar">
          <div class="doc-search" :class="{ has: docKeyword }">
            <span class="doc-search-ico">🔍</span>
            <input
              v-model="docKeyword"
              type="text"
              placeholder="搜索文档名称"
              @keyup.enter="onDocSearchNow"
            />
            <span v-if="docKeyword" class="doc-search-clear" @click="clearDocSearch">✕</span>
          </div>
          <span class="doc-search-count">{{ docKeyword ? `匹配 ${total} 个` : `共 ${total} 个文档` }}</span>
          <span v-if="kb && !canWrite" class="ro-tag" title="只读知识库，你仅有查看与下载权限">只读</span>
        </div>
        <div v-if="loading && !docs.length" class="empty">加载文档中...</div>
        <div v-else-if="!docs.length && docKeyword" class="empty">未找到匹配「{{ docKeyword }}」的文档</div>
        <div v-else-if="!docs.length" class="empty">暂无文档，请从知识库管理页上传</div>
        <div v-else class="doc-list">
          <article v-for="doc in docs" :key="doc.id" class="doc-row">
            <div class="doc-type">{{ fileTypeLabel(doc.filename) }}</div>
            <div class="doc-main">
              <h3>
                <template v-for="(pt, k) in highlightParts(doc.filename, docKeyword)" :key="k"
                  ><mark v-if="pt.hit">{{ pt.t }}</mark><template v-else>{{ pt.t }}</template></template>
                <span
                  v-if="doc.sourceArchiveName"
                  class="doc-from"
                  :title="`来自压缩包 ${doc.sourceArchiveName}`"
                >来自 {{ doc.sourceArchiveName }}</span>
              </h3>
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
              <button
                class="link-btn"
                :disabled="!isDownloadable(doc.parseStatus)"
                :title="isDownloadable(doc.parseStatus) ? '下载原文件' : '文档未处理完成，无法下载'"
                @click="onDownloadDoc(doc)"
              >
                下载
              </button>
              <button v-if="canWrite && doc.parseStatus === 'failed'" class="link-btn" @click="onReprocessDoc(doc)">重试</button>
              <button
                v-if="canWrite"
                class="link-btn danger"
                title="删除文档"
                @click="onDeleteDoc(doc)"
              >
                删除
              </button>
            </div>
          </article>
        </div>

        <Pager
          v-if="docs.length"
          :total="total"
          :page="page"
          :size="size"
          @update:page="loadDocs"
          @update:size="onDocSize"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageBreadcrumb from '../components/PageBreadcrumb.vue'
import DocumentStatusBadge from '../components/DocumentStatusBadge.vue'
import Pager from '../components/Pager.vue'
import { highlightParts } from '../utils/highlight'
import { listKb } from '../api/kb'
import { deleteDocument, downloadDocument, listDocuments, reprocessDocument } from '../api/document'
import { documentDetailRoute, parsePositiveId } from '../composables/useDocumentNav'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { useToast } from '../composables/useToast'
import { isDownloadable } from '../composables/useDocumentStatus'

const toast = useToast()

const route = useRoute()
const router = useRouter()
const kb = ref(null)
const kbLoaded = ref(false)
const docs = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const docKeyword = ref('')
let docSearchTimer = null

const kbId = computed(() => parsePositiveId(route.params.kbId))
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
// KB 已加载但不在当前可访问列表（无权限 / 不存在 / 不属于当前组织）→ 给明确提示，
// 而非误导性的“暂无文档，请上传”。
const kbInaccessible = computed(() => kbLoaded.value && !kb.value)

// 写权限：以后端返回的 myPermission(admin|write|read) 为准。
// 只读库(read / 公共库访客)隐藏删除、重试等写操作，仅保留查看与下载（后端也会 403 兜底）。
const canWrite = computed(() => {
  const p = kb.value?.myPermission
  return p === 'write' || p === 'admin'
})

const breadcrumbItems = computed(() => [
  { label: '知识库管理', to: '/knowledge' },
  { label: kb.value?.name || '…' },
  { label: '文档列表', current: true },
])

async function loadKb() {
  try {
    const res = await listKb()
    const list = res.data ?? []
    kb.value = list.find((item) => item.id === kbId.value) || null
    return list
  } catch {
    // 列表拉取失败（网络/鉴权瞬态）不做归属判定，返回 null 交由后续兜底
    return null
  } finally {
    kbLoaded.value = true
  }
}

async function loadDocs(nextPage = 1) {
  if (!kbId.value) {
    router.replace('/knowledge')
    return
  }
  loading.value = true
  try {
    // flatten=true：后端返回「解压出的子文档 + 独立文档」，排除压缩包容器本身，
    // 因此列表里平铺显示解压结果，不出现 isArchive 容器项。
    const res = await listDocuments(kbId.value, nextPage, size.value, docKeyword.value.trim() || undefined, true)
    docs.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
    page.value = res.data?.page ?? nextPage
  } finally {
    loading.value = false
  }
}

function onDocSize(n) {
  size.value = n
  loadDocs(1)
}

watch(docKeyword, () => {
  clearTimeout(docSearchTimer)
  docSearchTimer = setTimeout(() => loadDocs(1), 250)
})
function onDocSearchNow() {
  clearTimeout(docSearchTimer)
  loadDocs(1)
}
function clearDocSearch() {
  docKeyword.value = ''
  clearTimeout(docSearchTimer)
  loadDocs(1)
}

function goDoc(id) {
  router.push(documentDetailRoute(id, {
    from: 'documents',
    kbId: kbId.value,
    returnTo: route.fullPath,
  }))
}

async function onReprocessDoc(doc) {
  await reprocessDocument(doc.id)
  await loadDocs(page.value)
  await loadKb()
}

async function onDeleteDoc(doc) {
  const ok = await confirmDialog({
    title: '删除文档',
    message: `确定删除文档「${doc.filename}」？`,
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteDocument(doc.id)
    const nextPage = docs.value.length === 1 && page.value > 1 ? page.value - 1 : page.value
    await loadDocs(nextPage)
    await loadKb()
    toast.success('文档已删除')
  } catch {
    // 全局拦截器已 toast
  }
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

async function refreshPage() {
  // OS-B1：组织切换是整页刷新且保留 URL，旧组织的 kbId 会残留到新组织。
  // loadKb 用的是按当前组织过滤的 listKb —— 若成功拿到列表却不含当前 kbId，
  // 说明该库不属于当前组织，直接回退到当前组织的知识库列表，
  // 不再发出必然 403 的文档请求（避免误导性的“无权访问”弹窗 + 锁态）。
  const kbList = await loadKb()
  if (Array.isArray(kbList) && !kb.value) {
    toast.info('该知识库不属于当前组织，已返回知识库列表')
    router.replace('/knowledge')
    return
  }
  await loadDocs(1)
}

onMounted(refreshPage)

watch(kbId, (nextKbId, prevKbId) => {
  if (prevKbId == null || nextKbId === prevKbId) return
  refreshPage()
})
</script>

<style scoped>
mark { background: #fde68a; color: #78350f; border-radius: 3px; padding: 0 1px; }
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

.doc-toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.doc-search { position: relative; }
.doc-search input { height: 38px; width: 320px; max-width: 60vw; padding: 0 34px; border: 1px solid var(--border); border-radius: 10px; font-size: 13px; background: #fff; outline: none; transition: .15s; }
.doc-search input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft, #eff4ff); }
.doc-search-ico { position: absolute; left: 11px; top: 50%; transform: translateY(-50%); color: var(--text-muted); font-size: 13px; pointer-events: none; }
.doc-search-clear { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); color: var(--text-muted); cursor: pointer; font-size: 12px; }
.doc-search-clear:hover { color: var(--slate); }
.doc-search-count { font-size: 12.5px; color: var(--text-muted); }
.ro-tag { display: inline-flex; align-items: center; padding: 2px 9px; border-radius: var(--radius-full, 999px); background: rgba(148, 163, 184, 0.18); color: #64748b; font-size: 11px; font-weight: 700; border: 1px solid rgba(148, 163, 184, 0.25); }

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

/* 子文档来源标识：纯展示、不可点击、不带下载。独立文档(sourceArchiveName 为空)不渲染此标签。 */
.doc-from {
  display: inline-flex;
  align-items: center;
  margin-left: 6px;
  padding: 2px 9px;
  border-radius: var(--radius-full, 999px);
  background: var(--primary-soft, #eff4ff);
  color: var(--primary);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.6;
  vertical-align: middle;
  white-space: nowrap;
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
  color: var(--primary);
  padding: 6px 9px;
  font-size: 12px;
  cursor: pointer;
}

.link-btn.danger {
  color: var(--red);
  border-color: #fecaca;
}

.link-btn:disabled {
  color: var(--text-muted);
  border-color: var(--border);
  background: #f8fafc;
  cursor: not-allowed;
  opacity: 0.55;
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
