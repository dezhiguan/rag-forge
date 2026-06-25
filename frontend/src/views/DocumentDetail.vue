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
            <div class="doc-title-group">
              <StatusBadge :status="doc.parseStatus" :error="doc.errorMsg" />
              <h1 class="doc-title">{{ doc.filename }}</h1>
            </div>
            <div class="doc-header-actions">
              <button
                type="button"
                class="link-btn"
                :disabled="!canRechunk || rechunking"
                :title="canRechunk ? '重新分块' : '当前状态不能重新分块'"
                @click="confirmRechunk"
              >
                {{ rechunking ? '提交中…' : '重新分块' }}
              </button>
              <button
                v-if="cleanReport"
                type="button"
                class="link-btn"
                :title="showCleanPanel ? '收起清洗对比' : '查看清洗对比'"
                @click="showCleanPanel = !showCleanPanel"
              >
                {{ showCleanPanel ? '收起清洗对比' : '清洗对比' }}
              </button>
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
          </div>
        </header>

        <!--
          外部身份信息只对来自外部系统（MCP / 爬虫 / ETL）的文档展示；
          UI 上传不会填 externalId/sourceUrl，整段隐藏避免一排"—"。
          md5 + 来源通道挪到下面的"文档元信息"面板，作为文件基础属性始终可见。
        -->
        <section v-if="hasExternalIdentity" class="identity-section">
          <div class="section-title">外部身份信息</div>
          <div class="meta-list">
            <div v-if="doc.externalId" class="meta-row">
              <span class="meta-key">externalId</span>
              <span class="meta-val">{{ doc.externalId }}</span>
            </div>
            <div v-if="doc.sourceUrl" class="meta-row">
              <span class="meta-key">sourceUrl</span>
              <span class="meta-val">
                <a :href="doc.sourceUrl" target="_blank" rel="noopener noreferrer">{{ doc.sourceUrl }}</a>
              </span>
            </div>
          </div>
        </section>

        <div v-if="isCompletedDoc" class="doc-layout">
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
                  <span v-if="c.chunkModality" class="chunk-modality">{{ c.chunkModality }}</span>
                  <span class="chunk-tokens">{{ c.chunkerStrategy || (c.chunkModality?.startsWith('IMAGE') ? 'IMAGE_PIPELINE' : 'FIXED_WINDOW') }} · {{ c.tokenCount ?? 0 }} tokens</span>
                </div>
                <!--
                  IMAGE chunk 缩略图来源：
                  - 嵌入图（HTML/PDF/Word 抽出来的）：后端给 c.imageUrl（OSS presigned GET，10 分钟有效）
                  - 纯图片文档（fileType=image/*）：直接用整篇下载 URL，那本身就是这张图
                -->
                <img
                  v-if="c.imageUrl || (isImageDoc && c.imageKey)"
                  class="chunk-thumb"
                  :src="c.imageUrl || downloadUrl"
                  alt="chunk 预览图"
                >
                <div v-if="c.headingPath" class="chunk-heading">{{ c.headingPath }}</div>
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
                <span class="meta-key">分块策略</span>
                <span class="meta-val">
                  <span v-if="chunkerSummary">{{ chunkerSummary.text }}</span>
                  <span v-else class="meta-muted">—</span>
                </span>
              </div>
              <template v-if="showFixedSizeParams">
                <div class="meta-row">
                  <span class="meta-key">块大小</span>
                  <span class="meta-val">{{ doc.chunkSize != null ? doc.chunkSize + ' 字符' : '-' }}</span>
                </div>
                <div class="meta-row">
                  <span class="meta-key">块重叠</span>
                  <span class="meta-val">{{ doc.chunkOverlap != null ? doc.chunkOverlap + ' 字符' : '-' }}</span>
                </div>
              </template>
              <div class="meta-row">
                <span class="meta-key">上传时间</span>
                <span class="meta-val">{{ formatTime(doc.createdAt) }}</span>
              </div>
              <div class="meta-row">
                <span class="meta-key">知识库</span>
                <span class="meta-val">{{ doc.kbName || '-' }}</span>
              </div>
              <div v-if="doc.ingestSource" class="meta-row">
                <span class="meta-key">来源通道</span>
                <span class="meta-val">{{ doc.ingestSource }}</span>
              </div>
              <div v-if="doc.contentMd5" class="meta-row">
                <span class="meta-key">内容 md5</span>
                <span class="meta-val"><code>{{ doc.contentMd5 }}</code></span>
              </div>
              <div class="meta-row">
                <span class="meta-key">状态</span>
                <span class="meta-val">
                  <StatusBadge :status="doc.parseStatus" :error="doc.errorMsg" />
                </span>
              </div>
            </div>
            <div class="search-action" @click="$router.push({ path: '/debug', query: { kbId: doc.kbId, docId: doc.id, docFilename: doc.filename } })">🔍 在此文档中检索 →</div>
          </div>
        </div>

        <div v-else-if="isFailedDoc" class="processing-panel failed">
          <div class="processing-title">处理失败</div>
          <StatusBadge :status="doc.parseStatus" :error="doc.errorMsg" />
          <p class="processing-error">{{ doc.errorMsg || '未知错误' }}</p>
          <button class="btn-primary btn-retry" :disabled="retrying" @click="confirmReprocess">
            {{ retrying ? '提交中…' : '重新处理' }}
          </button>
        </div>

        <div v-else class="processing-panel">
          <div class="processing-spinner">⟳</div>
          <div class="processing-title">处理中，请稍候…</div>
          <div class="processing-status">
            当前阶段：
            <StatusBadge :status="doc.parseStatus" />
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

  <Teleport to="body">
    <transition name="clean-modal-fade">
      <div v-if="cleanReport && showCleanPanel" class="clean-modal-mask" @click.self="showCleanPanel = false">
        <div class="clean-modal" role="dialog" aria-modal="true">
          <header class="clean-modal-head">
            <div class="clean-modal-title">
              <span>📊 清洗对比</span>
              <span class="clean-modal-filename">{{ doc?.filename }}</span>
            </div>
            <button class="clean-modal-close" aria-label="关闭" @click="showCleanPanel = false">✕</button>
          </header>

          <div class="clean-modal-body">
            <div class="clean-metrics">
              <span>原文 <strong>{{ cleanReport.originalLength ?? 0 }}</strong> 字</span>
              <span>清洗后 <strong>{{ cleanReport.cleanedLength ?? 0 }}</strong> 字</span>
              <span>移除 <strong>{{ (cleanReport.removedRegions || []).length }}</strong> 处</span>
            </div>
            <div v-if="Object.keys(cleanReport.piiHits || {}).length" class="pii-hits">
              <span v-for="[key, count] in Object.entries(cleanReport.piiHits || {})" :key="key">
                {{ piiLabel(key) }} {{ count }}
              </span>
            </div>

            <div class="clean-compare">
              <div class="clean-col">
                <div class="clean-col-title">原文样本</div>
                <pre class="clean-text">{{ cleanReport.originalSample || '—' }}</pre>
              </div>
              <div class="clean-col">
                <div class="clean-col-title">清洗后样本</div>
                <pre class="clean-text"><template v-for="line in cleanDiffLines" :key="line.index"><span :class="{ 'diff-line': line.changed }">{{ line.text }}</span>
</template></pre>
              </div>
            </div>

            <div v-if="(cleanReport.removedRegions || []).length" class="removed-list">
              <div class="removed-list-title">被清洗管道剔除的段落（前 8 条）</div>
              <div v-for="(r, idx) in (cleanReport.removedRegions || []).slice(0, 8)" :key="idx" class="removed-item">
                <span class="removed-tag">{{ removedReasonLabel(r.reason) }}</span>
                <code>{{ summarizeContent(r.text || '') }}</code>
              </div>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>

  <RechunkDialog
    v-model="showRechunkDialog"
    :doc="doc"
    :chunks="chunks"
    @submit="onRechunkSubmit"
  />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { useToast } from '../composables/useToast'

const toast = useToast()
import PageBreadcrumb from '../components/PageBreadcrumb.vue'
import RechunkDialog from '../components/RechunkDialog.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { KB_DOCUMENT_DELETE_ENABLED } from '../config/uiPolicy'
import { deleteDocument, getDocument, listDocumentChunks, reprocessDocument, rechunkDocument } from '../api/document'

const deleteEnabled = KB_DOCUMENT_DELETE_ENABLED
import { navigateBackFromDocument } from '../composables/useDocumentNav'
import { useDocumentPolling } from '../composables/useDocumentPolling'
import {
  isTerminal,
  normalizeDocStatus,
  summarizeContent,
} from '../composables/useDocumentStatus'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const retrying = ref(false)
const rechunking = ref(false)
const showRechunkDialog = ref(false)
const showCleanPanel = ref(false)
const doc = ref(null)
const chunks = ref([])
const chunkPage = ref(1)
const chunkSize = 20
const chunkTotal = ref(0)
const loadingChunks = ref(false)
const chunkError = ref(false)
const { start: startPolling, stop: stopPolling } = useDocumentPolling()

const hasMoreChunks = computed(() => chunks.value.length < chunkTotal.value)
const normalizedStatus = computed(() => normalizeDocStatus(doc.value?.parseStatus))
const isCompletedDoc = computed(() => normalizedStatus.value === 'completed')
const isFailedDoc = computed(() => normalizedStatus.value === 'failed')
const isImageDoc = computed(() => (doc.value?.fileType || '').toLowerCase().startsWith('image/'))
const downloadUrl = computed(() => doc.value?.id ? `/api/v1/documents/${doc.value.id}/download` : '')
const canRechunk = computed(() => doc.value && !['pending', 'processing', 'reprocessing'].includes(normalizedStatus.value))
// 外部身份信息只对真正来自外部系统的文档展示。UI 上传永远没有这两个字段，
// 折叠整段比留一排"—"更干净；md5 + 来源通道挪到了"文档元信息"面板。
const hasExternalIdentity = computed(() =>
  Boolean(doc.value?.externalId || doc.value?.sourceUrl),
)
const CHUNKER_STRATEGY_LABELS = {
  MARKDOWN_HEADING: '按标题分块',
  FIXED_WINDOW: '固定窗口',
  RECURSIVE: '递归切分',
  SEMANTIC: '语义分块',
  TABLE_AWARE: '表格感知',
  IMAGE_PIPELINE: '图像管道',
}

const STRATEGIES_USING_FIXED_PARAMS = new Set(['FIXED_WINDOW', 'RECURSIVE'])

const chunkerSummary = computed(() => {
  const list = chunks.value || []
  if (list.length === 0) return null
  const counts = new Map()
  for (const c of list) {
    const key =
      c.chunkerStrategy ||
      (c.chunkModality?.startsWith('IMAGE') ? 'IMAGE_PIPELINE' : 'FIXED_WINDOW')
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1])
  const dominant = sorted[0][0]
  const label = CHUNKER_STRATEGY_LABELS[dominant] || dominant
  if (sorted.length === 1) {
    return { text: label, isMixed: false, dominant }
  }
  const others = sorted.slice(1).map(([k]) => CHUNKER_STRATEGY_LABELS[k] || k)
  return {
    text: `${label} · 混合 ${sorted.length} 种（含 ${others.join('、')}）`,
    isMixed: true,
    dominant,
  }
})

const showFixedSizeParams = computed(() => {
  const s = chunkerSummary.value
  if (!s) return false
  return STRATEGIES_USING_FIXED_PARAMS.has(s.dominant)
})

const cleanReport = computed(() => parseCleanReport(doc.value?.cleanReportJson))
const cleanDiffLines = computed(() => {
  const original = (cleanReport.value?.originalSample || '').split('\n')
  const cleaned = (cleanReport.value?.cleanedSample || '').split('\n')
  return cleaned.map((line, index) => ({
    index,
    text: line,
    changed: line.trim() !== '' && line !== original[index],
  }))
})

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
  // 关掉所有 Teleport 弹层，避免离开页面后遮罩残留导致整页空白
  showCleanPanel.value = false
  showRechunkDialog.value = false
  // 立刻停止本页定时器，避免轮询打到已卸载组件
  const id = Number(route.params.id)
  if (id) stopPolling(id)
  navigateBackFromDocument(router, route, doc.value?.kbId)
}

async function loadDetail() {
  loading.value = true
  try {
    const id = route.params.id
    const res = await getDocument(id)
    doc.value = res.data ?? null
    resetChunks()
    if (normalizeDocStatus(doc.value?.parseStatus) === 'completed') {
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
      if (normalizeDocStatus(status.parseStatus) === 'completed') {
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

function onKeydown(e) {
  if (e.key === 'Escape' && showCleanPanel.value) {
    showCleanPanel.value = false
  }
}

onMounted(async () => {
  await loadDetail()
  setupPolling()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  const id = Number(route.params.id)
  if (id) stopPolling(id)
  window.removeEventListener('keydown', onKeydown)
})

async function onDeleteDoc() {
  if (!deleteEnabled || !doc.value?.id) return
  const ok = await confirmDialog({
    title: '删除文档',
    message: `确定删除文档「${doc.value.filename}」？`,
    detail: '该文档及其所有分块、检索索引将被永久删除，此操作不可恢复。',
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteDocument(doc.value.id)
    toast.success('文档已删除')
    onBack()
  } catch {
    // 全局拦截器已 toast
  }
}

async function confirmReprocess() {
  if (!doc.value || retrying.value) return
  const ok = await confirmDialog({
    title: '重新处理文档',
    message: `确定重新处理文档「${doc.value.filename}」？`,
    detail: '系统会重新解析、分块并触发向量化，期间该文档不可检索。',
    confirmText: '重新处理',
    cancelText: '取消',
    variant: 'warning',
  })
  if (!ok) return
  await onReprocess()
}

async function onReprocess() {
  if (!doc.value) return
  retrying.value = true
  try {
    const res = await reprocessDocument(doc.value.id)
    doc.value.parseStatus = res.data?.status || res.status || 'PENDING'
    doc.value.errorMsg = null
    doc.value.chunkCount = 0
    resetChunks()
    setupPolling()
    toast.success('已提交重新处理')
  } catch {
    // 全局拦截器已 toast
  } finally {
    retrying.value = false
  }
}

function confirmRechunk() {
  if (!doc.value || !canRechunk.value || rechunking.value) return
  showRechunkDialog.value = true
}

async function onRechunkSubmit(payload = {}) {
  if (!doc.value) return
  showRechunkDialog.value = false
  rechunking.value = true
  try {
    const res = await rechunkDocument(doc.value.id, payload)
    doc.value.parseStatus = res.data?.status || res.status || 'REPROCESSING'
    doc.value.errorMsg = null
    doc.value.chunkCount = 0
    resetChunks()
    setupPolling()
    if (payload.imageOnly) {
      toast.success('已提交重新处理图像')
    } else {
      const strategy = payload.strategy || res.newStrategy || res.data?.newStrategy
      const label = CHUNKER_STRATEGY_LABELS[strategy] || strategy || '默认策略'
      toast.success(`已提交重新分块（${label}）`)
    }
  } catch {
    // 全局拦截器已 toast
  } finally {
    rechunking.value = false
  }
}

async function onRechunk() {
  await onRechunkSubmit({})
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

const REMOVED_REASON_LABELS = {
  REPEATED_HEADER_FOOTER: '重复页眉页脚',
  WATERMARK: '水印',
  TOC: '目录',
  REPEATED_EDGE_LINE: '边缘重复行',
}

function removedReasonLabel(reason) {
  return REMOVED_REASON_LABELS[reason] || reason || '未知'
}

function parseCleanReport(raw) {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function piiLabel(key) {
  return ({
    phone: '手机号',
    idCard: '身份证',
    email: '邮箱',
    bankCard: '银行卡',
  })[key] || key
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
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.doc-title-group {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
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
  margin: 0;
  min-width: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--slate);
  word-break: break-all;
}


.doc-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: stretch;
  min-height: calc(100vh - 200px);
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.doc-left,
.doc-right {
  padding: 20px;
  min-height: 0;
  overflow-y: auto;
}

.doc-left {
  border-right: 1px solid var(--border);
}

.doc-right {
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
  align-items: center;
  gap: 8px;
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
  margin-left: auto;
}

.chunk-modality {
  border: 1px solid rgba(245, 158, 11, 0.34);
  border-radius: 6px;
  background: rgba(245, 158, 11, 0.1);
  color: #92400e;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 6px;
}

.chunk-thumb {
  width: 100%;
  max-height: 220px;
  object-fit: contain;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #f8fafc;
  margin: 4px 0 8px;
}

.chunk-heading {
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--blue);
  word-break: break-word;
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
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
  font-size: 12px;
}

.meta-key {
  color: var(--text-muted);
  width: 110px;
  flex-shrink: 0;
}

.meta-val {
  min-width: 0;
  font-weight: 500;
  word-break: break-word;
}

.meta-muted {
  color: var(--text-muted);
  font-weight: 400;
}

.meta-val a {
  color: var(--blue);
}

.meta-val code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  color: #334155;
  word-break: break-all;
}

.identity-section {
  margin-bottom: 16px;
  padding: 16px 20px 4px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
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

.clean-modal-mask {
  position: fixed;
  inset: 0;
  z-index: 999;
  display: grid;
  place-items: center;
  background: rgba(15, 23, 42, 0.5);
  padding: 24px;
}

.clean-modal {
  width: min(1100px, 100%);
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.25);
  overflow: hidden;
}

.clean-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border);
}

.clean-modal-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}
.clean-modal-title > span:first-child {
  font-weight: 600;
  font-size: 15px;
  color: #0f172a;
}
.clean-modal-filename {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.clean-modal-close {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 18px;
  cursor: pointer;
  border-radius: 6px;
  display: grid;
  place-items: center;
}
.clean-modal-close:hover {
  background: rgba(148, 163, 184, 0.18);
  color: #1e293b;
}

.clean-modal-body {
  padding: 18px 20px 22px;
  overflow-y: auto;
  flex: 1 1 auto;
}

.clean-modal-fade-enter-active,
.clean-modal-fade-leave-active {
  transition: opacity 0.2s ease;
}
.clean-modal-fade-enter-from,
.clean-modal-fade-leave-to {
  opacity: 0;
}
.clean-modal-fade-enter-active .clean-modal,
.clean-modal-fade-leave-active .clean-modal {
  transition: transform 0.2s ease;
}
.clean-modal-fade-enter-from .clean-modal,
.clean-modal-fade-leave-to .clean-modal {
  transform: scale(0.96);
}

.clean-metrics strong {
  color: #1e293b;
  font-weight: 700;
}

.removed-list-title {
  margin: 14px 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
}

.clean-metrics,
.pii-hits {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.clean-metrics span,
.pii-hits span {
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 6px;
  background: #fff;
  padding: 3px 7px;
  font-size: 11px;
  color: var(--text-muted);
}

.pii-hits span {
  border-color: rgba(220, 38, 38, 0.22);
  background: #fef2f2;
  color: #991b1b;
}

.clean-compare {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}

.clean-col {
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  overflow: hidden;
}

.clean-col-title {
  padding: 7px 9px;
  border-bottom: 1px solid var(--border);
  background: #f8fafc;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.clean-col:first-child .clean-col-title::before {
  content: '📥 ';
}

.clean-col:last-child .clean-col-title::before {
  content: '✨ ';
}

.clean-text {
  margin: 0;
  max-height: 380px;
  overflow: auto;
  padding: 10px 12px;
  white-space: pre-wrap;
  word-break: break-word;
  color: #334155;
  font-size: 12px;
  line-height: 1.6;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.diff-line {
  display: inline-block;
  width: 100%;
  padding: 0 4px;
  background: #fef9c3;
  color: #854d0e;
  border-left: 2px solid #facc15;
  box-sizing: border-box;
}

.diff-line:empty {
  display: none;
}

.removed-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 10px;
}

.removed-item {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 6px;
  background: #fff;
  padding: 6px 10px;
  font-size: 11px;
}

.removed-item .removed-tag {
  flex: 0 0 auto;
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  background: #f1f5f9;
  color: #475569;
  font-weight: 600;
  font-size: 11px;
  white-space: nowrap;
}

.removed-item code {
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

  .doc-layout {
    min-height: auto;
  }

  .doc-left {
    border-right: none;
    border-bottom: 1px solid var(--border);
    padding: 14px;
  }

  .doc-right {
    padding: 14px;
  }

  .clean-compare {
    grid-template-columns: 1fr;
  }

  .removed-item {
    grid-template-columns: 1fr;
  }

  .doc-header {
    padding: 12px 16px;
  }

  .doc-title {
    font-size: 16px;
    line-height: 1.45;
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
