<template>
  <div>
    <div class="page-body">
      <div class="detail-nav">
        <button class="btn btn-secondary" type="button" @click="onBack">← 返回上一页</button>
      </div>

      <PageBreadcrumb :items="breadcrumbItems" />

      <div v-if="loading && !doc" class="state-hint">
        <div class="state-icon">⏳</div>
        <div class="state-title">加载中...</div>
      </div>

      <div v-else-if="doc && orgMismatch" class="state-hint">
        <div class="state-icon">🔒</div>
        <div class="state-title">该文档不属于当前组织</div>
        <div class="state-desc">请切换到该文档所属的组织后再查看。</div>
        <button class="btn btn-secondary" type="button" style="margin-top:12px" @click="onBack">← 返回上一页</button>
      </div>

      <template v-else-if="doc">
        <header class="doc-header">
          <div class="doc-header-main">
            <div class="doc-title-group">
              <div class="doc-title-badges">
                <span v-if="isArchiveContainer" class="archive-status" :class="archiveStatusClass">{{ archiveStatusLabel }}</span>
                <StatusBadge v-else :status="doc.parseStatus" :error="doc.errorMsg" />
                <span v-if="!canWrite" class="ro-tag" title="只读知识库，你仅有查看与下载权限">只读</span>
              </div>
              <h1 class="doc-title">
                <span class="doc-title-text">{{ doc.filename }}</span>
                <span v-if="isArchiveContainer" class="chip">压缩包</span>
                <span v-else-if="doc.sourceArchiveName" class="from-tag">来自 {{ doc.sourceArchiveName }}</span>
              </h1>
            </div>
            <div class="doc-header-actions">
              <button
                v-if="isCompletedDoc"
                type="button"
                class="link-btn meta-btn"
                title="查看文档元信息"
                @click="metaOpen = true"
              >
                📊 文档元信息
              </button>
              <button
                v-if="canWrite && !isArchiveContainer"
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
                v-if="canWrite"
                type="button"
                class="link-btn danger"
                title="删除文档"
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

        <!--
          压缩包容器（isArchive / EXPANDING / EXPANDED / FAILED）：本身没有 chunk，
          不能走"分块预览"，也不能走通用"处理中"分支（EXPANDED/FAILED 是终态）。
          展示展开结果徽标 + 状态 + 跳过条目，而不是无限转圈 + 无意义的"重新分块"。
        -->
        <div v-if="isArchiveContainer" class="archive-panel" :class="{ failed: archiveFailed }">
          <div v-if="archiveExpanding" class="archive-expanding">
            <span class="processing-spinner">⟳</span>
            <span>正在解压并入库压缩包内的文档，请稍候…</span>
          </div>

          <div v-if="childrenSummary" class="archive-badges">
            <span class="badge badge-green">{{ childrenSummary.completed ?? 0 }} 成功</span>
            <span class="badge" :class="(childrenSummary.failed ?? 0) > 0 ? 'badge-red' : 'badge-gray'">{{ childrenSummary.failed ?? 0 }} 失败</span>
            <span class="badge" :class="(childrenSummary.skipped ?? 0) > 0 ? 'badge-amber' : 'badge-gray'">{{ childrenSummary.skipped ?? 0 }} 跳过</span>
          </div>

          <p v-if="archiveFailed" class="archive-error">
            展开失败：{{ translateErrorCode(doc.errorMsg) || doc.errorMsg || '压缩包无法解压' }}
          </p>
          <p v-else-if="archiveExpanded" class="archive-hint">
            压缩包已解压并入库 {{ childrenSummary?.completed ?? 0 }} 个文档，可在检索中命中。
          </p>

          <div v-if="skippedEntries.length" class="archive-skipped">
            <div class="archive-skipped-title">跳过的条目（{{ skippedEntries.length }}）</div>
            <div v-for="(e, i) in skippedEntries" :key="i" class="archive-skipped-item">
              <span class="removed-tag">{{ skipReasonLabel(e.reason) }}</span>
              <code>{{ e.path }}</code>
            </div>
          </div>
        </div>

        <div v-else-if="isCompletedDoc" class="doc-chunks-full">
          <div class="section-title">📄 Chunks</div>
          <div class="doc-toolbar">
            <div class="doc-search" :class="{ has: chunkKeyword }">
              <span class="doc-search-ico">🔍</span>
              <input
                v-model="chunkKeyword"
                type="text"
                placeholder="模糊搜索块内容，或输入 #编号 定位（如 #12）"
                @input="onChunkSearchInput"
              />
              <span v-if="chunkKeyword" class="doc-search-clear" @click="clearChunkSearch">✕</span>
            </div>
            <span class="doc-search-count">{{ chunkKeyword ? `匹配 ${chunkTotal} 块` : `共 ${chunkTotal} 块` }}</span>
          </div>
          <div v-if="!chunks.length && loadingChunks" class="state-hint" style="padding:24px 0">
            <div class="state-desc">正在加载分块数据...</div>
          </div>
          <div v-else-if="chunkError" class="state-hint" style="padding:24px 0">
            <div class="state-desc">
              分块加载失败，<button class="chunk-load-btn" @click="loadChunksPage(chunkPage)">点击重试</button>
            </div>
          </div>
          <div v-else-if="!chunks.length && chunkKeyword" class="state-hint" style="padding:24px 0">
            <div class="state-desc">没有匹配「{{ chunkKeyword }}」的块，换个关键词试试</div>
          </div>
          <div v-else-if="!chunks.length" class="state-hint" style="padding:24px 0">
            <div class="state-desc">文档处理完成后将显示分块数据</div>
          </div>
          <div v-else>
            <div v-for="c in chunks" :key="c.chunkIndex" class="chunk-card clickable" :class="{ hit: !!chunkKeyword }" title="点击查看完整内容" @click="openChunk(c)">
              <div class="chunk-head">
                <span class="chunk-title">#{{ c.chunkIndex }}</span>
                <span v-if="c.chunkModality" class="chunk-modality">{{ c.chunkModality }}</span>
                <span class="chunk-tokens">{{ chunkerStrategyLabel(c.chunkerStrategy || (c.chunkModality?.startsWith('IMAGE') ? 'IMAGE_PIPELINE' : 'FIXED_WINDOW')) }} · {{ c.tokenCount ?? 0 }} tokens</span>
              </div>
              <!--
                IMAGE chunk 缩略图来源：
                - 嵌入图（HTML/PDF/Word 抽出来的）：后端给 c.imageUrl（OSS presigned GET，10 分钟有效）
                - 纯图片文档（fileType=image/*）：直接用整篇下载 URL，那本身就是这张图
              -->
              <img
                v-if="chunkThumbSrc(c)"
                class="chunk-thumb"
                :src="chunkThumbSrc(c)"
                alt="chunk 预览图"
              >
              <div v-if="c.headingPath" class="chunk-heading">{{ c.headingPath }}</div>
              <!--
                TEXT chunk 里的 ![image N](rfimg://N) 占位符按 figureIndex 反查 IMAGE chunk
                的 imageUrl，inline 显示成 <img>；命中关键词的文字段用 <mark> 高亮。
                用模板拼接 + <mark>，不用 v-html，避免 chunk 文本里的尖括号 / 脚本被当 HTML 注入。
              -->
              <div class="chunk-text" :title="c.content">
                <template v-for="(seg, i) in renderChunkSegments(c, figureMap)" :key="i">
                  <img
                    v-if="seg.type === 'image'"
                    class="chunk-inline-img"
                    :src="seg.url"
                    :alt="seg.alt"
                  >
                  <template v-else>
                    <template v-for="(pt, k) in highlightParts(seg.text)" :key="k"
                      ><mark v-if="pt.hit">{{ pt.t }}</mark><template v-else>{{ pt.t }}</template></template>
                  </template>
                </template>
              </div>
            </div>
            <Pager
              :total="chunkTotal"
              :page="chunkPage"
              :size="chunkSize"
              :size-options="[5, 10, 20, 50]"
              unit="块"
              flush
              @update:page="goChunkPage"
              @update:size="setChunkSize"
            />
          </div>
        </div>

        <div v-else-if="isFailedDoc" class="processing-panel failed">
          <div class="processing-title">处理失败</div>
          <StatusBadge :status="doc.parseStatus" :error="doc.errorMsg" />
          <p class="processing-error">{{ translateErrorCode(doc.errorMsg) || doc.errorMsg || '未知错误' }}</p>
          <button v-if="canWrite" class="btn-primary btn-retry" :disabled="retrying" @click="confirmReprocess">
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

  <!-- 文档元信息：右侧滑出抽屉（V4 抽屉版） -->
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="metaOpen && doc" class="drawer-overlay" @click.self="metaOpen = false">
        <div class="drawer">
          <div class="drawer-head">
            <span class="drawer-title">📊 文档元信息</span>
            <button class="drawer-x" type="button" title="关闭" @click="metaOpen = false">✕</button>
          </div>
          <div class="drawer-body">
            <div class="meta-list">
              <div class="meta-row">
                <span class="meta-key">文件名</span>
                <span class="meta-val">
                  {{ doc.filename }}
                  <span v-if="doc.sourceArchiveName" class="from-tag">来自 {{ doc.sourceArchiveName }}</span>
                </span>
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
      </div>
    </transition>
  </Teleport>

  <!-- 分块全文弹窗 -->
  <ContentModal
    v-model="chunkModal"
    :title="activeChunk ? `#${activeChunk.chunkIndex}` : ''"
    :content="activeChunk?.content || ''"
  >
    <template #meta>
      <span v-if="activeChunk?.chunkModality" class="chunk-modality">{{ activeChunk.chunkModality }}</span>
      <span class="chunk-tokens">{{ chunkerStrategyLabel(activeChunk?.chunkerStrategy || (activeChunk?.chunkModality?.startsWith('IMAGE') ? 'IMAGE_PIPELINE' : 'FIXED_WINDOW')) }} · {{ activeChunk?.tokenCount ?? 0 }} tokens</span>
    </template>
  </ContentModal>

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
import Pager from '../components/Pager.vue'
import ContentModal from '../components/ContentModal.vue'
import RechunkDialog from '../components/RechunkDialog.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { deleteDocument, getDocument, listDocumentChunks, reprocessDocument, rechunkDocument, downloadDocument } from '../api/document'
import { getKb } from '../api/kb'
import { highlightParts as hlParts } from '../utils/highlight'
import { chunkerStrategyLabel } from '../utils/chunker'
import { translateErrorCode } from '../api/error-messages'
import { navigateBackFromDocument } from '../composables/useDocumentNav'
import { useOrg } from '../composables/useOrg'
import { useDocumentPolling } from '../composables/useDocumentPolling'
import {
  isTerminal,
  normalizeDocStatus,
  summarizeContent,
} from '../composables/useDocumentStatus'

const route = useRoute()
const router = useRouter()
const { currentOrgId, isPlatform } = useOrg()
const loading = ref(false)
const retrying = ref(false)
const rechunking = ref(false)
const showRechunkDialog = ref(false)
const showCleanPanel = ref(false)
const doc = ref(null)
// 写权限：以所属 KB 的 myPermission(admin|write|read) 为准。
// 只读库隐藏重新分块/重新处理/删除等写操作，保留清洗对比这类纯查看能力（后端也会 403 兜底）。
const kbPermission = ref(null)
const canWrite = computed(() => kbPermission.value === 'write' || kbPermission.value === 'admin')
// 组织视角隔离：文档所属 KB 的组织与“当前组织”不一致时（KbAccessGuard 按 KB 权限放行、但组织视角要求隔离），
// 拦截正文展示，避免切到其它组织后仍看到原组织文档内容（操作误归属）。平台视图放行。
const kbOrgId = ref(null)
const orgMismatch = computed(
  () => !isPlatform.value && kbOrgId.value != null && currentOrgId.value != null && kbOrgId.value !== currentOrgId.value,
)
const chunks = ref([])
const chunkPage = ref(1)
const chunkSize = ref(10)
const chunkTotal = ref(0)
const loadingChunks = ref(false)
const chunkError = ref(false)
const chunkKeyword = ref('')
const metaOpen = ref(false)
const chunkModal = ref(false)
const activeChunk = ref(null)
let chunkSearchTimer = null

function openChunk(c) {
  activeChunk.value = c
  chunkModal.value = true
}
const { start: startPolling, stop: stopPolling } = useDocumentPolling()

const normalizedStatus = computed(() => normalizeDocStatus(doc.value?.parseStatus))
const isCompletedDoc = computed(() => normalizedStatus.value === 'completed')
const isFailedDoc = computed(() => normalizedStatus.value === 'failed')
const isImageDoc = computed(() => (doc.value?.fileType || '').toLowerCase().startsWith('image/'))
// 纯图片文档的预览：后端 imageUrl 在本地盘存储下为 file:// 或空（浏览器不可加载），
// 而 /download 需 JWT 鉴权，<img src> 带不了内存里的 token → 401 裂图。
// 解决：用带鉴权的 request 拉整篇图片为 blob，再转 objectURL 给 <img>（把登录态带过去）。
const localImageBlobUrl = ref('')
const isHttpUrl = (u) => typeof u === 'string' && /^https?:\/\//.test(u)
// chunk 缩略图最终 src：优先可用的 http(s) presigned URL；否则纯图片文档用带鉴权拉取的 blob。
function chunkThumbSrc(c) {
  if (isHttpUrl(c?.imageUrl)) return c.imageUrl
  if (isImageDoc.value && c?.imageKey) return localImageBlobUrl.value
  return ''
}
function revokeLocalImage() {
  if (localImageBlobUrl.value) {
    URL.revokeObjectURL(localImageBlobUrl.value)
    localImageBlobUrl.value = ''
  }
}
async function resolveLocalImage() {
  revokeLocalImage()
  if (!isImageDoc.value || !doc.value?.id) return
  try {
    const res = await downloadDocument(doc.value.id)
    const raw = res?.data ?? res
    if (raw instanceof Blob) {
      // /download 返回 application/octet-stream + nosniff，blob 会继承该类型导致 <img> 不解码。
      // 重贴成文档真实图片 MIME（如 image/webp），确保浏览器按图片解码。
      const typed =
        raw.type && raw.type.startsWith('image/')
          ? raw
          : new Blob([raw], { type: doc.value.fileType || 'image/*' })
      localImageBlobUrl.value = URL.createObjectURL(typed)
    }
  } catch {
    /* 拉取失败则退化为无预览，不影响 chunk 列表 */
  }
}

// 压缩包容器：以 VO.isArchive 为准；EXPANDING/EXPANDED 是容器专属状态，也一并识别。
// FAILED 单靠状态无法区分容器与普通文档失败，只由 isArchive 判定，避免误伤普通失败文档。
const isArchiveContainer = computed(
  () => Boolean(doc.value?.isArchive) || ['expanding', 'expanded'].includes(normalizedStatus.value),
)
// 容器"处理中"= 仅 EXPANDING；EXPANDED / FAILED 都是终态（不再无限转圈）。
const archiveExpanding = computed(() => isArchiveContainer.value && normalizedStatus.value === 'expanding')
const archiveExpanded = computed(() => isArchiveContainer.value && normalizedStatus.value === 'expanded')
const archiveFailed = computed(() => isArchiveContainer.value && normalizedStatus.value === 'failed')
const childrenSummary = computed(() => doc.value?.childrenSummary || null)
const skippedEntries = computed(() => doc.value?.skippedEntries || [])
const archiveStatusLabel = computed(() => {
  if (archiveFailed.value) return '展开失败'
  if (archiveExpanding.value) return '展开中'
  return '已展开'
})
const archiveStatusClass = computed(() => {
  if (archiveFailed.value) return 'is-failed'
  if (archiveExpanding.value) return 'is-processing'
  return 'is-completed'
})

// 压缩包内被跳过条目的原因中文映射（契约约定）。
const SKIP_REASON_LABELS = {
  nested_archive: '嵌套压缩包',
  illegal_path: '非法路径',
  unsupported_type: '不支持的类型',
  oversize: '超大文件',
  register_failed: '入库失败',
}
function skipReasonLabel(reason) {
  return SKIP_REASON_LABELS[normalizeDocStatus(reason)] || reason || '未知'
}

// figureIndex -> imageUrl 映射，TEXT chunk 里的 ![image N](rfimg://N) 占位符按 N 反查这张表
// 拿到真实预签 URL inline 渲染。来源是同一篇文档所有已加载 IMAGE chunk 的 figureIndex + imageUrl。
const figureMap = computed(() => {
  const map = new Map()
  for (const c of chunks.value) {
    if (c?.imageUrl && Number.isInteger(c?.figureIndex)) {
      map.set(c.figureIndex, c.imageUrl)
    }
  }
  return map
})

const RFIMG_PATTERN = /!\[([^\]]*)\]\(rfimg:\/\/(\d+)\)/g

// IMAGE chunk 不走占位符渲染（它本身就是图），直接给一个 text 段返回原文 / 占位文字。
// TEXT chunk 按 RFIMG_PATTERN 切段：text → image → text → image ...
// 图查不到（minBytes 过滤掉、figureMap 没命中）时保留原占位符可见，避免静默"假装啥都没有"。
function renderChunkSegments(chunk, figMap) {
  const content = chunk?.content || ''
  if ((chunk?.chunkModality || '').toUpperCase().startsWith('IMAGE')) {
    return [{ type: 'text', text: summarizeContent(content) }]
  }
  if (!RFIMG_PATTERN.test(content)) {
    return [{ type: 'text', text: summarizeContent(content) }]
  }
  RFIMG_PATTERN.lastIndex = 0
  const segs = []
  let last = 0
  let m
  while ((m = RFIMG_PATTERN.exec(content)) !== null) {
    if (m.index > last) {
      segs.push({ type: 'text', text: content.slice(last, m.index) })
    }
    const figIdx = parseInt(m[2], 10)
    const url = figMap.get(figIdx)
    if (url) {
      segs.push({ type: 'image', url, alt: m[1] || `image ${figIdx}` })
    } else {
      // 图缺失（被 minBytes 过滤 / 当前页还没加载到对应 IMAGE chunk），把占位符当文本显示
      segs.push({ type: 'text', text: m[0] })
    }
    last = m.index + m[0].length
  }
  if (last < content.length) {
    segs.push({ type: 'text', text: content.slice(last) })
  }
  return segs
}
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
    await loadKbPermission()
    resetChunks()
    resolveLocalImage()
    if (normalizeDocStatus(doc.value?.parseStatus) === 'completed') {
      await loadChunksPage(1)
    }
  } finally {
    loading.value = false
  }
}

async function loadKbPermission() {
  const kbId = doc.value?.kbId
  if (!kbId) {
    kbPermission.value = null
    kbOrgId.value = null
    return
  }
  try {
    const res = await getKb(kbId)
    kbPermission.value = res.data?.myPermission ?? null
    // 记录文档所属 KB 的组织，用于组织视角隔离判定（见 orgMismatch）。
    kbOrgId.value = res.data?.orgId ?? null
  } catch {
    // 拿不到权限时按只读处理，宁可少给写入口（后端仍有 403 兜底）
    kbPermission.value = null
    kbOrgId.value = null
  }
}

function resetChunks() {
  chunks.value = []
  chunkPage.value = 1
  chunkTotal.value = doc.value?.chunkCount ?? 0
  chunkError.value = false
  chunkKeyword.value = ''
}

// 输入即筛（防抖 250ms），与文档/知识库列表搜索一致；实际过滤在后端按 content 搜，#编号 定位由后端识别。
function onChunkSearchInput() {
  if (chunkSearchTimer) clearTimeout(chunkSearchTimer)
  chunkSearchTimer = setTimeout(() => loadChunksPage(1), 250)
}

function clearChunkSearch() {
  if (chunkSearchTimer) clearTimeout(chunkSearchTimer)
  chunkKeyword.value = ''
  loadChunksPage(1)
}

function goChunkPage(p) {
  loadChunksPage(p)
}

function setChunkSize(n) {
  chunkSize.value = n
  loadChunksPage(1)
}

// 按页加载（分页器版）：整页替换，不再累加。空关键词=全部；内容模糊/#编号 定位均由后端过滤。
// 请求序号 last-wins：不因"加载中"丢弃新请求（否则搜索/翻页会被在飞行中的初始加载吞掉），
// 只应用最新一次请求的响应，过期响应丢弃，避免竞态覆盖。
let chunkReqSeq = 0
async function loadChunksPage(page = chunkPage.value) {
  if (!doc.value) return
  const seq = ++chunkReqSeq
  loadingChunks.value = true
  chunkError.value = false
  try {
    const res = await listDocumentChunks(doc.value.id, page, chunkSize.value, chunkKeyword.value)
    if (seq !== chunkReqSeq) return
    const data = res.data ?? {}
    chunkTotal.value = data.total ?? 0
    chunks.value = data.list ?? []
    chunkPage.value = data.page ?? page
  } catch {
    if (seq === chunkReqSeq) chunkError.value = true
  } finally {
    if (seq === chunkReqSeq) loadingChunks.value = false
  }
}

// 命中关键词高亮（#编号 定位不高亮内容）——复用共享 util，按当前搜索词切片。
function highlightParts(text) {
  return hlParts(text, chunkKeyword.value)
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
      // completed=普通文档处理完（拉 chunk）；expanded=压缩包展开完（拉 childrenSummary/skippedEntries）。
      // 两者都要 loadDetail 刷新，status 轮询本身不返回容器展开明细。
      const s = normalizeDocStatus(status.parseStatus)
      if (s === 'completed' || s === 'expanded') {
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
  revokeLocalImage()
  window.removeEventListener('keydown', onKeydown)
})

async function onDeleteDoc() {
  if (!doc.value?.id) return
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

.doc-title-badges {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ro-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: var(--radius-full, 999px);
  background: rgba(148, 163, 184, 0.18);
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid rgba(148, 163, 184, 0.25);
}

.link-btn {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--primary);
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
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.doc-title-text {
  min-width: 0;
  word-break: break-all;
}

/* 子文档来源标识：纯标识，不可点、不带下载 */
.from-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-full, 999px);
  background: var(--primary-soft, #eaf1ff);
  color: var(--primary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

/* 压缩包标签 */
.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 6px;
  background: rgba(99, 102, 241, 0.12);
  color: #6366f1;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

/* 容器状态胶囊（复用 StatusBadge 配色，本组件独立定义） */
.archive-status {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid transparent;
}
.archive-status.is-processing {
  background: #dbeafe;
  color: #1d4ed8;
  border-color: rgba(29, 78, 216, 0.2);
}
.archive-status.is-completed {
  background: #dcfce7;
  color: #166534;
  border-color: rgba(22, 101, 52, 0.2);
}
.archive-status.is-failed {
  background: #fee2e2;
  color: #991b1b;
  border-color: rgba(153, 27, 27, 0.22);
}

.archive-panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 24px 22px;
}

.archive-expanding {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--text-muted);
}

.archive-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.archive-hint {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
}

.archive-error {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: #b91c1c;
  line-height: 1.6;
  word-break: break-word;
}

.archive-skipped {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.archive-skipped-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
}

.archive-skipped-item {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 6px;
  background: #fff;
  padding: 6px 10px;
  font-size: 11px;
}

.archive-skipped-item .removed-tag {
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

.archive-skipped-item code {
  color: #334155;
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

/* V4 抽屉版：Chunks 占满整宽单栏 */
.doc-chunks-full {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 20px;
}

/* 顶部动作栏「📊 文档元信息」按钮（略强调，区别于纯文字链接） */
.meta-btn {
  border: 1px solid var(--primary-soft, #bfdbfe);
  color: var(--primary);
  background: var(--primary-soft, #eff6ff);
  border-radius: 8px;
  padding: 4px 10px;
}
.meta-btn:hover { background: #e0ecff; }

/* 分块卡片可点击查看全文 */
.chunk-card.clickable { cursor: pointer; transition: border-color .12s, box-shadow .12s; }
.chunk-card.clickable:hover { border-color: var(--primary-soft, #bfdbfe); box-shadow: 0 1px 6px rgba(37, 99, 235, .08); }

/* 命中关键词高亮 */
.chunk-card.hit { border-color: var(--primary-soft, #bfdbfe); background: #fbfdff; }
.chunk-text :deep(mark), .chunk-text mark { background: #fde68a; color: #78350f; border-radius: 3px; padding: 0 1px; }

/* 文档元信息：右侧滑出抽屉 */
.drawer-overlay { position: fixed; inset: 0; z-index: 60; background: rgba(15, 23, 42, .42); display: flex; justify-content: flex-end; }
.drawer { width: min(440px, 94vw); height: 100%; background: #fff; box-shadow: -10px 0 34px rgba(15, 23, 42, .16); display: flex; flex-direction: column; overflow: hidden; }
.drawer-head { display: flex; align-items: center; gap: 10px; padding: 16px 20px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.drawer-title { font-weight: 600; font-size: 15px; color: var(--slate); }
.drawer-x { margin-left: auto; cursor: pointer; border: none; background: none; font-size: 18px; color: var(--text-muted); line-height: 1; }
.drawer-x:hover { color: var(--slate); }
.drawer-body { padding: 8px 20px 20px; overflow: auto; }
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: background .22s ease; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform .26s cubic-bezier(.4, 0, .2, 1); }
.drawer-fade-enter-from, .drawer-fade-leave-to { background: rgba(15, 23, 42, 0); }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }

.section-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 14px;
  color: var(--slate);
}

/* 分块搜索工具栏：搜索框 + 计数，样式对齐文档/知识库列表（.doc-search / .doc-search-count） */
.doc-toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.doc-search { position: relative; }
.doc-search input { height: 34px; width: 260px; max-width: 60vw; padding: 0 32px; border: 1px solid var(--border); border-radius: 10px; font-size: 13px; background: #fff; outline: none; transition: .15s; }
.doc-search input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft, #eff4ff); }
.doc-search-ico { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--text-muted); font-size: 13px; pointer-events: none; }
.doc-search-clear { position: absolute; right: 9px; top: 50%; transform: translateY(-50%); color: var(--text-muted); cursor: pointer; font-size: 12px; }
.doc-search-clear:hover { color: var(--slate); }
.doc-search-count { font-size: 12.5px; color: var(--text-muted); white-space: nowrap; }

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
  color: var(--primary);
  word-break: break-word;
}

.chunk-text {
  color: var(--gray);
  white-space: pre-wrap;
  word-break: break-word;
}

/* inline 嵌入图：跟段落文字穿插显示，区别于 chunk-thumb 那种独占整行的预览图 */
.chunk-inline-img {
  display: block;
  max-width: 100%;
  max-height: 280px;
  margin: 8px 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #f8fafc;
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
  color: var(--primary);
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
  color: var(--primary);
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
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  text-align: center;
  color: var(--primary);
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
  background: var(--primary);
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
