<template>
  <div>
    <div class="page-body">
      <div class="top-toolbar">
        <div class="toolbar-left">
          <button class="btn btn-primary" @click="openCreate">+ 创建知识库</button>
          <button class="btn btn-secondary" :disabled="loadingKb" @click="loadKbs">刷新</button>
          <button
            v-if="isAdmin"
            class="btn btn-sm"
            :class="adminViewAll ? 'btn-primary' : 'btn-secondary'"
            :title="adminViewAll ? '正在以管理员身份查看全部知识库（已留审计）' : '管理员破玻璃：查看全部用户的知识库（将被审计）'"
            @click="toggleAdminViewAll"
          >
            {{ adminViewAll ? '✓ 查看全部·已留痕' : '查看全部(管理员)' }}
          </button>
        </div>
      </div>

      <div v-if="loadingKb" class="state-hint">
        <div class="state-icon">⏳</div>
        <div class="state-title">加载中...</div>
      </div>
      <div v-else-if="!kbList.length" class="state-hint">
        <div class="state-icon">📁</div>
        <div class="state-title">暂无知识库</div>
        <div class="state-desc">创建知识库并上传文档，开始构建检索能力</div>
      </div>

      <div v-else class="content">
        <div v-if="!uploadableKbs.length" class="no-writable-hint">
          公共知识库为只读，你暂无可写入的知识库。请先「创建知识库」后再上传文档。
        </div>
        <div v-else class="upload-layout">
          <div class="upload-settings">
            <label class="select-label">
              上传到知识库
              <select v-model="uploadKbId" class="select">
                <option v-for="kb in uploadableKbs" :key="kb.id" :value="kb.id">
                  {{ kb.name }}
                </option>
              </select>
            </label>
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
              accept=".pdf,.doc,.docx,.md,.markdown,.html,.htm,.txt,.png,.jpg,.jpeg,.gif,.webp,application/pdf,text/plain,image/*"
              @change="onFileChange"
            />
            <div class="upload-icon">📤</div>
            <div class="upload-text">
              拖拽文件上传 · 文档（PDF / Word / Markdown / HTML / TXT）+ 图片（PNG / JPG / GIF / WEBP，自动 OCR）
            </div>
            <div class="upload-hint">单文件最大 50MB · 支持批量上传</div>
          </div>

          <div v-if="uploadItems.length" class="upload-queue">
            <article
              v-for="item in uploadItems"
              :key="item.id"
              class="upload-item"
              :class="{ failed: item.status === 'failed', done: item.status === 'done' }"
            >
              <div class="upload-item-main">
                <div class="upload-item-title">{{ item.file.name }}</div>
                <div class="upload-item-meta">
                  {{ formatBytes(item.file.size) }} · {{ phaseLabel(item.phase) }}
                </div>
                <div v-if="item.errorMessage" class="upload-item-error">{{ item.errorMessage }}</div>
              </div>
              <div class="upload-progress-cell">
                <div class="upload-progress-text">{{ item.progress }}%</div>
                <div class="upload-progress-bar">
                  <span :style="{ width: `${item.progress}%` }" />
                </div>
                <div v-if="item.status === 'failed'" class="upload-retry-actions">
                  <button class="btn btn-secondary btn-sm" @click.stop="dismissUploadItem(item)">
                    取消
                  </button>
                  <button class="btn btn-secondary btn-sm" @click.stop="retryUploadItem(item)">
                    重试
                  </button>
                  <button
                    v-if="item.errorCode === 'DOC_IDENTITY_CONFLICT'"
                    class="btn btn-secondary btn-sm"
                    @click.stop="retryUploadItem(item, 'REPLACE')"
                  >
                    覆盖
                  </button>
                  <button
                    v-if="item.errorCode === 'DOC_IDENTITY_CONFLICT'"
                    class="btn btn-secondary btn-sm"
                    @click.stop="skipUploadItem(item)"
                  >
                    跳过
                  </button>
                </div>
              </div>
            </article>
          </div>
        </div>

        <div class="pipeline-bar" aria-label="processing pipeline">
          <div class="pipeline-step" :class="stepClass('parsing')">
            <span class="step-icon">📥</span>
            <span class="step-label">解析</span>
            <span class="step-sub">PDF→Text</span>
          </div>
          <span class="pipe-arrow" :class="stepClass('parsing')">→</span>
          <div class="pipeline-step" :class="stepClass('chunking')">
            <span class="step-icon">✂️</span>
            <span class="step-label">分块</span>
            <span class="step-sub">512 tokens</span>
          </div>
          <span class="pipe-arrow" :class="stepClass('chunking')">→</span>
          <div class="pipeline-step" :class="stepClass('embedding')">
            <span class="step-icon">🧮</span>
            <span class="step-label">向量化</span>
            <span class="step-sub">Embedding</span>
          </div>
          <span class="pipe-arrow" :class="stepClass('embedding')">→</span>
          <div class="pipeline-step" :class="stepClass('indexing')">
            <span class="step-icon">📇</span>
            <span class="step-label">BM25</span>
            <span class="step-sub">ES 索引</span>
          </div>
          <span class="pipe-arrow" :class="stepClass('indexing')">→</span>
          <div class="pipeline-step" :class="stepClass('completed')">
            <span class="step-icon">✅</span>
            <span class="step-label">可用</span>
            <span class="step-sub">可检索</span>
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
                        <span class="kb-name-row">
                          <strong>{{ kb.name }}</strong>
                          <span
                            v-if="kb.orgId"
                            class="kb-tag kb-tag-org"
                            title="组织库"
                            data-test="kb-org-badge"
                          >🏢 组织·{{ kb.orgName || '组织库' }}</span>
                          <span
                            v-if="kb.visibility === 'ORG'"
                            class="kb-tag kb-tag-orgvis"
                            data-test="kb-orgvis-badge"
                          >组织可见</span>
                          <span
                            v-else-if="kb.visibility === 'PUBLIC'"
                            class="kb-tag kb-tag-pub"
                            data-test="kb-public-badge"
                          >公开</span>
                        </span>
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
                    <span v-if="canWriteKb(kb)" class="link-action" @click.stop="openEdit(kb)">编辑</span>
                    <span
                      v-if="canAdminKb(kb)"
                      class="link-action danger"
                      :class="{ 'is-disabled': !kbDeleteEnabled }"
                      :title="kbDeleteEnabled ? '删除知识库' : '演示环境已禁用删除'"
                      @click.stop="onDeleteKb(kb)"
                    >
                      删除
                    </span>
                    <span v-if="!canWriteKb(kb)" class="ro-tag" title="公共知识库，你只有只读权限">只读</span>
                  </td>
                </tr>

                <tr v-if="expandedKbId === kb.id" class="docs-row">
                  <td colspan="6">
                    <div class="docs-panel">
                      <div class="docs-head">
                        <div>
                          <div class="docs-title">最近上传文档</div>
                          <div class="docs-sub">默认展示最新 3 条，完整列表可分页查看</div>
                        </div>
                        <div class="docs-actions">
                          <button
                            class="btn btn-secondary btn-sm"
                            :disabled="docsLoading[kb.id]"
                            @click.stop="loadDocs(kb.id)"
                          >
                            刷新
                          </button>
                          <button class="btn btn-secondary btn-sm" @click.stop="goDocuments(kb.id)">
                            查看更多文档
                          </button>
                        </div>
                      </div>

                      <div v-if="docsLoading[kb.id]" class="state-hint" style="padding:20px 0">
                        <div class="state-desc">加载文档中...</div>
                      </div>
                      <div v-else-if="(docsMap[kb.id]?.list || []).length === 0" class="state-hint" style="padding:20px 0">
                        <div class="state-desc">上传文档后自动解析并建立索引</div>
                      </div>
                      <div v-else class="recent-doc-list">
                        <article v-for="doc in docsMap[kb.id]?.list || []" :key="doc.id" class="recent-doc-card">
                          <div class="doc-type">{{ fileTypeLabel(doc.filename) }}</div>
                          <div class="doc-main">
                            <div class="doc-title-row">
                              <div class="doc-name">{{ doc.filename }}</div>
                              <div
                                class="doc-status-cell"
                                :title="normalizeDocStatus(doc.parseStatus) === 'failed' ? doc.errorMsg || '处理失败' : undefined"
                              >
                                <span v-if="isProcessing(doc.parseStatus)" class="status-icon spin">⟳</span>
                                <span v-else-if="normalizeDocStatus(doc.parseStatus) === 'completed'" class="status-icon ok">✓</span>
                                <span v-else-if="normalizeDocStatus(doc.parseStatus) === 'failed'" class="status-icon fail">✗</span>
                                <span class="badge" :class="docStatusClass(doc.parseStatus)">
                                  {{ docStatusLabel(doc.parseStatus) }}
                                </span>
                              </div>
                            </div>
                            <div class="doc-meta">
                              {{ formatBytes(doc.fileSize) }} · v{{ doc.version ?? 1 }} · {{ doc.chunkCount ?? 0 }} chunks · {{ formatTime(doc.createdAt) }}
                            </div>
                            <div v-if="normalizeDocStatus(doc.parseStatus) === 'failed' && doc.errorMsg" class="doc-error">
                              {{ doc.errorMsg }}
                            </div>
                            <div class="doc-links">
                              <span class="link-action" @click.stop="goDoc(doc.id, doc.kbId)">详情</span>
                              <span
                                class="link-action"
                                :class="{ 'is-disabled': !isDownloadable(doc.parseStatus) }"
                                :title="isDownloadable(doc.parseStatus) ? '下载原文件' : '文档未处理完成，无法下载'"
                                @click.stop="isDownloadable(doc.parseStatus) && onDownloadDoc(doc)"
                              >
                                下载
                              </span>
                              <span v-if="normalizeDocStatus(doc.parseStatus) === 'failed' && canWriteKb(kb)" class="link-action" @click.stop="onReprocessDoc(doc)">重试</span>
                              <span
                                v-if="canWriteKb(kb)"
                                class="link-action danger"
                                :class="{ 'is-disabled': !deleteEnabled }"
                                :title="deleteEnabled ? '删除文档' : '演示环境已禁用删除'"
                                @click.stop="onDeleteDoc(doc)"
                              >
                                删除
                              </span>
                            </div>
                          </div>
                        </article>
                      </div>
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
          <label class="field">
            <span>归属</span>
            <select v-model="kbForm.orgId" @change="onOwnerChange" data-test="kb-form-owner">
              <option :value="null" data-test="kb-form-owner-personal">个人（我自己）</option>
              <option
                v-for="org in manageableOrgs"
                :key="org.id"
                :value="org.id"
                data-test="kb-form-owner-org"
              >
                组织：{{ org.name }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>可见性</span>
            <select v-model="kbForm.visibility" data-test="kb-form-visibility">
              <option v-for="opt in visibilityOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>应答模型（可选）</span>
            <select v-model="kbForm.answerModel">
              <option value="">使用默认模型</option>
              <option v-for="model in answerModels" :key="model" :value="model">
                {{ model }}
              </option>
            </select>
          </label>
          <label class="field field-toggle">
            <span>多模态处理</span>
            <span class="toggle-row">
              <input type="checkbox" v-model="kbForm.imageModeOn" />
              <span class="toggle-hint">
                开启后，HTML/PDF/Word 内嵌图与纯图片文档会走 OCR + 向量化入库；关闭只走文本管道。
              </span>
            </span>
          </label>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showCreate = false">取消</button>
            <button class="btn btn-primary" :disabled="submittingKb" @click="onCreateKb">
              确定
            </button>
          </div>
        </div>
      </div>

      <div v-if="showEdit" class="modal-mask" @click.self="showEdit = false">
        <div class="modal">
          <h3 class="modal-title">编辑知识库</h3>
          <label class="field">
            <span>名称 *</span>
            <input v-model="editForm.name" type="text" placeholder="知识库名称" />
          </label>
          <label class="field">
            <span>描述</span>
            <textarea v-model="editForm.description" rows="3" placeholder="可选" />
          </label>
          <div class="edit-grid">
            <label class="field" :class="{ invalid: editChunkSizeError }">
              <span class="field-label-with-hint">
                分块大小（字符）
                <span
                  class="hint-icon"
                  tabindex="0"
                  role="button"
                  aria-label="分块大小说明"
                  @click.prevent="toggleHint('chunkSize')"
                  @blur="closeHint('chunkSize')"
                >
                  ⓘ
                  <span v-if="openHint === 'chunkSize'" class="hint-popover">
                    <strong>仅对部分策略生效</strong>
                    <p>该值只在「固定窗口」「递归切分」策略下被读取。</p>
                    <p>当前文档大多走「按标题分块」「语义分块」「表格感知」等结构化策略，会忽略此值。</p>
                    <p class="hint-tip">想真正生效？在文档详情页点「重新分块」时选择对应策略。</p>
                  </span>
                </span>
              </span>
              <input
                v-model.number="editForm.chunkSize"
                type="number"
                :min="CHUNK_SIZE_MIN"
                :max="CHUNK_SIZE_MAX"
                step="1"
                :placeholder="`${CHUNK_SIZE_MIN}-${CHUNK_SIZE_MAX}`"
                @keydown="blockNegativeNumberKey"
                @blur="onEditChunkSizeBlur"
              />
              <span v-if="editChunkSizeError" class="field-error">{{ editChunkSizeError }}</span>
            </label>
            <label class="field" :class="{ invalid: editChunkOverlapError }">
              <span class="field-label-with-hint">
                分块重叠（字符）
                <span
                  class="hint-icon"
                  tabindex="0"
                  role="button"
                  aria-label="分块重叠说明"
                  @click.prevent="toggleHint('chunkOverlap')"
                  @blur="closeHint('chunkOverlap')"
                >
                  ⓘ
                  <span v-if="openHint === 'chunkOverlap'" class="hint-popover">
                    <strong>仅对部分策略生效</strong>
                    <p>该值只在「固定窗口」「递归切分」策略下被读取，控制相邻 chunk 间重叠字符数。</p>
                    <p>「按标题」「语义」「表格感知」等策略会忽略此值。</p>
                    <p class="hint-tip">想真正生效？在文档详情页点「重新分块」时选择「固定窗口」或「递归切分」。</p>
                  </span>
                </span>
              </span>
              <input
                v-model.number="editForm.chunkOverlap"
                type="number"
                :min="CHUNK_OVERLAP_MIN"
                :max="CHUNK_OVERLAP_MAX"
                step="1"
                :placeholder="`${CHUNK_OVERLAP_MIN}-${CHUNK_OVERLAP_MAX}`"
                @keydown="blockNegativeNumberKey"
                @blur="onEditChunkOverlapBlur"
              />
              <span v-if="editChunkOverlapError" class="field-error">{{ editChunkOverlapError }}</span>
            </label>
          </div>
          <div class="edit-grid">
            <label class="field">
              <span>应答模式</span>
              <select v-model="editForm.answerMode">
                <option v-for="mode in answerModes" :key="mode" :value="mode">{{ mode }}</option>
              </select>
            </label>
            <label class="field">
              <span>应答模型</span>
              <select v-model="editForm.answerModel">
                <option value="">使用 KB 默认</option>
                <option v-for="model in answerModels" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
          </div>
          <label class="field field-toggle">
            <span>多模态处理</span>
            <span class="toggle-row">
              <input type="checkbox" v-model="editForm.imageModeOn" />
              <span class="toggle-hint">
                开启后，HTML/PDF/Word 内嵌图与纯图片文档会走 OCR + 向量化入库；关闭只走文本管道。
              </span>
            </span>
          </label>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showEdit = false">取消</button>
            <button class="btn btn-primary" :disabled="submittingKb" @click="onUpdateKb">
              {{ submittingKb ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { KB_DOCUMENT_DELETE_ENABLED, KNOWLEDGE_BASE_DELETE_ENABLED } from '../config/uiPolicy'
import { createKb, deleteKb, listKb, updateKb } from '../api/kb'
import { listOrgs } from '../api/org'
import {
  deleteDocument,
  downloadDocument,
  listDocuments,
  reprocessDocument,
  uploadDocument,
} from '../api/document'
import { uploadErrorCode, uploadErrorMessage } from '../api/upload'
import { useDocumentPolling } from '../composables/useDocumentPolling'
import { documentDetailRoute } from '../composables/useDocumentNav'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { useToast } from '../composables/useToast'
import { useAuth } from '../composables/useAuth'
import {
  CHUNK_OVERLAP_MAX,
  CHUNK_OVERLAP_MIN,
  CHUNK_SIZE_MAX,
  CHUNK_SIZE_MIN,
  blockNegativeNumberKey,
  chunkOverlapError,
  chunkSizeError,
  normalizeChunkOverlap,
  normalizeChunkSize,
} from '../utils/chunkParams'

const toast = useToast()

const kbDeleteEnabled = KNOWLEDGE_BASE_DELETE_ENABLED
const deleteEnabled = KB_DOCUMENT_DELETE_ENABLED
import {
  docStatusClass,
  docStatusLabel,
  isDownloadable,
  isProcessing,
  isTerminal,
  normalizeDocStatus,
} from '../composables/useDocumentStatus'

const router = useRouter()
const route = useRoute()
const { start: startDocPolling, stop: stopDocPolling } = useDocumentPolling()

const { ragRole } = useAuth()
const isAdmin = computed(() => ragRole.value === 'ADMIN')
const adminViewAll = ref(false)
const adminOverrideReason = ref('')

const kbList = ref([])
const loadingKb = ref(false)
const expandedKbId = ref(null)

const docsMap = reactive({})
const docsLoading = reactive({})

const uploadKbId = ref(null)
const isDragOver = ref(false)
const fileInputRef = ref(null)
const uploadItems = ref([])
let uploadItemSeq = 0
const activeDocId = ref(null)
const activeStatus = ref(null)
const pendingTrackQueue = ref([])

const STATUS_ORDER = ['pending', 'parsing', 'chunking', 'embedding', 'indexing', 'completed']
const UPLOAD_CONCURRENCY = 3

// 行级权限：以后端返回的 myPermission(admin|write|read) 为准。
// 公共/平台库对普通用户是 read → 不显示编辑/删除/上传入口（后端也会 403 兜底）。
function canWriteKb(kb) {
  const p = kb?.myPermission
  return p === 'write' || p === 'admin'
}
function canAdminKb(kb) {
  return kb?.myPermission === 'admin'
}
// 只能上传到自己有写权限的库
const uploadableKbs = computed(() => kbList.value.filter(canWriteKb))

const showCreate = ref(false)
const showEdit = ref(false)
const submittingKb = ref(false)
const openHint = ref(null)

// 我作为 OWNER/ADMIN 的组织（可在其下建组织库）
const manageableOrgs = ref([])
async function loadManageableOrgs() {
  try {
    const res = await listOrgs()
    const orgs = res?.data || res || []
    manageableOrgs.value = orgs.filter((o) => o.myRole === 'OWNER' || o.myRole === 'ADMIN')
  } catch (e) {
    manageableOrgs.value = []
  }
}
// 可见性选项随归属变化：个人库 PRIVATE/PUBLIC；组织库 PRIVATE/ORG
const visibilityOptions = computed(() =>
  kbForm.value.orgId
    ? [
        { value: 'PRIVATE', label: '私有（仅组织管理员）' },
        { value: 'ORG', label: '组织可见（全体成员可读）' },
      ]
    : [
        { value: 'PRIVATE', label: '私有（仅自己）' },
        { value: 'PUBLIC', label: '公开（全平台只读）' },
      ]
)
function onOwnerChange() {
  // 切换归属后，把可见性回退到该归属下的默认值
  kbForm.value.visibility = 'PRIVATE'
}

function toggleHint(key) {
  openHint.value = openHint.value === key ? null : key
}

function closeHint(key) {
  setTimeout(() => {
    if (openHint.value === key) openHint.value = null
  }, 150)
}
const kbForm = ref({ name: '', description: '', answerModel: '', imageModeOn: false, orgId: null, visibility: 'PRIVATE' })
const editForm = ref({
  id: null,
  name: '',
  description: '',
  chunkSize: null,
  chunkOverlap: null,
  answerMode: 'ON',
  answerModel: '',
  imageModeOn: false,
})
const answerModes = ['OFF', 'PREVIEW', 'ON']
const answerModels = ['qwen-plus', 'qwen-max']

const editChunkSizeError = computed(() => chunkSizeError(editForm.value.chunkSize))
const editChunkOverlapError = computed(() =>
  chunkOverlapError(editForm.value.chunkOverlap, editForm.value.chunkSize),
)

function onEditChunkSizeBlur() {
  editForm.value.chunkSize = normalizeChunkSize(editForm.value.chunkSize)
}

function onEditChunkOverlapBlur() {
  editForm.value.chunkOverlap = normalizeChunkOverlap(editForm.value.chunkOverlap)
}

async function toggleAdminViewAll() {
  if (!adminViewAll.value) {
    const reason = window.prompt('「查看全部知识库」属管理员越权操作，将被审计留痕。请填写访问原因：', '')
    if (!reason || !reason.trim()) return
    adminOverrideReason.value = reason.trim()
    adminViewAll.value = true
  } else {
    adminViewAll.value = false
    adminOverrideReason.value = ''
  }
  await loadKbs()
}

async function loadKbs() {
  loadingKb.value = true
  try {
    const res = await listKb(adminViewAll.value ? adminOverrideReason.value : undefined)
    kbList.value = res.data ?? []
    // 默认上传目标取第一个「可写」的库（不是第一个可见库，避免默认选到只读公共库）
    if ((!uploadKbId.value || !uploadableKbs.value.some((kb) => kb.id === uploadKbId.value)) && uploadableKbs.value.length) {
      uploadKbId.value = uploadableKbs.value[0].id
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

function stepClass(stepName) {
  if (!activeDocId.value) return ''
  const status = normalizeDocStatus(activeStatus.value)
  if (status === 'failed') return 'failed'

  const currentIdx = STATUS_ORDER.indexOf(status)
  const stepIdx = STATUS_ORDER.indexOf(stepName)

  if (stepIdx < currentIdx) return 'done'
  if (stepIdx === currentIdx) {
    return status === 'completed' ? 'done' : 'active'
  }
  if (stepIdx === currentIdx + 1) return 'next'
  return ''
}

function enqueueTracking(docId, kbId) {
  if (!docId || !kbId) return
  if (activeDocId.value === docId) return
  if (pendingTrackQueue.value.some((item) => item.docId === docId)) return
  pendingTrackQueue.value.push({ docId, kbId })
}

function ensureActiveTracking(docId, kbId, status = 'pending') {
  if (!docId || !kbId) return
  const normalized = normalizeDocStatus(status)
  if (!activeDocId.value) {
    activeDocId.value = docId
    activeStatus.value = normalized
    return
  }
  if (activeDocId.value === docId) {
    activeStatus.value = normalized
    return
  }
  enqueueTracking(docId, kbId)
}

function advanceTrackingQueue() {
  if (activeDocId.value && activeStatus.value && !isTerminal(activeStatus.value)) {
    return
  }
  const next = pendingTrackQueue.value.shift()
  if (!next) return
  activeDocId.value = next.docId
  activeStatus.value = 'pending'
}

function watchProcessingDocs(kbId) {
  const list = docsMap[kbId]?.list ?? []
  for (const doc of list) {
    if (!isProcessing(doc.parseStatus)) continue
    ensureActiveTracking(doc.id, kbId, doc.parseStatus)
    startDocPolling(
      doc.id,
      (status) => {
        applyStatusToDoc(kbId, doc.id, status)
        if (activeDocId.value === doc.id) {
          activeStatus.value = normalizeDocStatus(status.parseStatus)
          if (isTerminal(status.parseStatus)) {
            advanceTrackingQueue()
          }
        }
      },
      async () => {
        await loadDocs(kbId)
        await loadKbs()
      },
    )
  }
}

async function loadDocs(kbId) {
  if (!Number.isInteger(kbId) || kbId <= 0) return
  docsLoading[kbId] = true
  try {
    const res = await listDocuments(kbId, 1, 3)
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
  ensureActiveTracking(docId, kbId, 'pending')
  startDocPolling(
    docId,
    (status) => {
      applyStatusToDoc(kbId, docId, status)
      if (activeDocId.value === docId) {
        activeStatus.value = normalizeDocStatus(status.parseStatus)
        if (isTerminal(status.parseStatus)) {
          advanceTrackingQueue()
        }
      }
    },
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
  kbForm.value = {
    name: '',
    description: '',
    answerModel: '',
    imageModeOn: false,
    orgId: null,
    visibility: 'PRIVATE',
  }
  loadManageableOrgs()
  showCreate.value = true
}

async function onCreateKb() {
  if (!kbForm.value.name?.trim()) {
    toast.warning('请填写知识库名称')
    return
  }
  submittingKb.value = true
  try {
    await createKb({
      name: kbForm.value.name.trim(),
      description: kbForm.value.description || undefined,
      answerModel: kbForm.value.answerModel || undefined,
      imageProcessingMode: kbForm.value.imageModeOn ? 'ON' : 'OFF',
      orgId: kbForm.value.orgId || undefined,
      visibility: kbForm.value.visibility || undefined,
    })
    showCreate.value = false
    await loadKbs()
  } finally {
    submittingKb.value = false
  }
}

function openEdit(kb) {
  editForm.value = {
    id: kb.id,
    name: kb.name || '',
    description: kb.description || '',
    chunkSize: kb.chunkSize ?? null,
    chunkOverlap: kb.chunkOverlap ?? null,
    answerMode: kb.answerMode || 'ON',
    answerModel: kb.answerModel || '',
    imageModeOn: (kb.imageProcessingMode || '').toUpperCase() === 'ON',
  }
  showEdit.value = true
}

async function onUpdateKb() {
  if (!editForm.value.name?.trim()) {
    toast.warning('请填写知识库名称')
    return
  }
  onEditChunkSizeBlur()
  onEditChunkOverlapBlur()
  const paramError = editChunkSizeError.value || editChunkOverlapError.value
  if (paramError) {
    toast.warning(paramError)
    return
  }
  submittingKb.value = true
  try {
    await updateKb(editForm.value.id, {
      name: editForm.value.name.trim(),
      description: editForm.value.description || undefined,
      chunkSize: editForm.value.chunkSize || undefined,
      chunkOverlap: editForm.value.chunkOverlap ?? undefined,
      answerMode: editForm.value.answerMode || undefined,
      answerModel: editForm.value.answerModel || undefined,
      imageProcessingMode: editForm.value.imageModeOn ? 'ON' : 'OFF',
    })
    showEdit.value = false
    await loadKbs()
  } finally {
    submittingKb.value = false
  }
}

async function onDeleteKb(kb) {
  if (!kbDeleteEnabled) return
  const ok = await confirmDialog({
    title: '删除知识库',
    message: `确定删除知识库「${kb.name}」？`,
    detail: '该知识库内所有文档、分块和检索索引都会被永久清除，此操作不可恢复。',
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteKb(kb.id)
    await loadKbs()
    if (expandedKbId.value === kb.id) expandedKbId.value = null
    toast.success('知识库已删除')
  } catch {
    // 全局拦截器已 toast
  }
}

function onPickFile() {
  if (fileInputRef.value) fileInputRef.value.click()
}

async function handleFiles(files) {
  const kbIdNum = Number(uploadKbId.value)
  if (!Number.isInteger(kbIdNum) || kbIdNum <= 0) {
    toast.warning('请先选择上传到哪个知识库')
    return
  }
  if (!files || files.length === 0) return

  const kbId = kbIdNum
  const rawItems = files.map((file) => createUploadItem(file, kbId))
  uploadItems.value = [...rawItems, ...uploadItems.value]
  // 关键：传给 uploadOneItem 的必须是 uploadItems.value 里的 reactive proxy，
  // 不能是 createUploadItem 返回的原始对象。否则 item.phase / item.progress 的修改
  // 改不到 Vue 跟踪的 proxy 上，UI 一直停留在初始的 "排队中 0%"，直到末尾某个数组级
  // 操作触发整体 re-render 才一次性跳到 100。
  const liveItems = uploadItems.value.slice(0, rawItems.length)
  await runWithConcurrency(liveItems, UPLOAD_CONCURRENCY, (item) => uploadOneItem(item))
  await loadKbs()
  if (expandedKbId.value === kbId) {
    await loadDocs(kbId)
  } else if (kbId) {
    expandedKbId.value = kbId
    await loadDocs(kbId)
  }
}

function createUploadItem(file, kbId) {
  return {
    id: ++uploadItemSeq,
    kbId,
    file,
    progress: 0,
    phase: 'queued',
    status: 'queued',
    errorCode: null,
    errorMessage: '',
  }
}

async function uploadOneItem(item, onConflict = 'REJECT') {
  item.status = 'uploading'
  item.errorCode = null
  item.errorMessage = ''
  item.progress = 0
  try {
    const res = await uploadDocument(item.kbId, item.file, {
      onConflict,
      onProgress: (progress) => {
        item.progress = Math.max(item.progress, progress)
      },
      onPhaseChange: (phase) => {
        item.phase = phase
        item.progress = Math.max(item.progress, phaseProgress(phase))
      },
    })
    const payload = res.data
    const docId = payload?.documentId ?? payload?.document?.id
    item.phase = 'done'
    item.status = 'done'
    item.progress = 100
    if (docId) {
      beginPollingDoc(docId, item.kbId)
    }
  } catch (e) {
    const code = uploadErrorCode(e)
    // 服务端 SHA-256 判重命中：弹三选一让用户决定 覆盖/跳过/取消，
    // 避免默认 REJECT 把"重复上传"粗暴渲染成"上传失败"。
    if (code === 'DOC_IDENTITY_CONFLICT' && onConflict === 'REJECT') {
      const next = await promptConflictChoice(item.file?.name)
      if (next === 'REPLACE') {
        // 覆盖：真的要重新跑一次完整 hash + PUT + register，UI 卡片留下显示新进度
        await uploadOneItem(item, 'REPLACE')
        return
      }
      if (next === 'SKIP') {
        // 跳过：再发一次 register 让后端按 SKIP 走（safeDelete 掉新 PUT 的 OSS 对象，
        // 避免孤儿），但 UI 不留卡片，因为没有任何新文档入库。
        try { await uploadOneItem(item, 'SKIP') } catch { /* 静默：SKIP 失败也直接释放 UI 位 */ }
        dismissUploadItem(item)
        toast.success(`已跳过「${item.file?.name || '文件'}」（库内已有相同内容）`)
        return
      }
      // 取消：用户主动放弃，UI 直接释放；OSS 上多出来的孤儿由 OssOrphanCleanupJob 负责清理
      dismissUploadItem(item)
      return
    }
    item.status = 'failed'
    item.phase = 'failed'  // 避免 UI 卡在最后一次成功阶段的文案（如"计算指纹"）
    item.errorCode = code
    item.errorMessage = uploadErrorMessage(e)
    toast.error(item.errorMessage)
  }
}

async function promptConflictChoice(filename) {
  return confirmDialog({
    title: '已有相同内容的文档',
    message: filename
      ? `知识库中已存在与「${filename}」内容完全相同的文档。`
      : '知识库中已存在内容完全相同的文档。',
    detail: '覆盖：删除旧文档并重新处理；跳过：保留旧文档不重复入库；取消：放弃本次上传。',
    cancelText: '取消',
    choices: [
      { value: 'SKIP', label: '跳过', variant: 'default' },
      { value: 'REPLACE', label: '覆盖', variant: 'danger' },
    ],
    cancelValue: 'CANCEL',
  })
}

async function retryUploadItem(item, onConflict = 'REJECT') {
  await uploadOneItem(item, onConflict)
  await refreshAfterUpload(item.kbId)
}

function skipUploadItem(item) {
  item.status = 'done'
  item.phase = 'done'
  item.progress = 100
  item.errorCode = null
  item.errorMessage = ''
}

function dismissUploadItem(item) {
  uploadItems.value = uploadItems.value.filter((entry) => entry.id !== item.id)
}

async function refreshAfterUpload(kbId) {
  await loadKbs()
  if (expandedKbId.value === kbId) {
    await loadDocs(kbId)
  }
}

async function runWithConcurrency(items, limit, worker) {
  let index = 0
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (index < items.length) {
      const item = items[index]
      index += 1
      await worker(item)
    }
  })
  await Promise.all(runners)
}

function phaseProgress(phase) {
  const map = {
    queued: 0,
    hashing: 2,
    presigning: 8,
    uploading: 10,
    relay: 15,
    registering: 96,
    done: 100,
  }
  return map[phase] ?? 0
}

function phaseLabel(phase) {
  const map = {
    queued: '排队中',
    hashing: '计算指纹',
    presigning: '申请直传地址',
    uploading: '上传 OSS',
    relay: '服务端上传',
    registering: '登记文档',
    done: '完成',
    failed: '失败',
  }
  return map[phase] || phase || '-'
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

function goDoc(id, kbId) {
  router.push(documentDetailRoute(id, {
    from: 'knowledge',
    kbId,
    returnTo: route.fullPath,
  }))
}

function goDocuments(kbId) {
  const id = Number(kbId)
  if (!Number.isInteger(id) || id <= 0) return
  router.push(`/knowledge/${id}/documents`)
}

function fileTypeLabel(filename) {
  const ext = (filename || '').split('.').pop()?.toUpperCase()
  if (!ext || ext === filename) return 'DOC'
  if (ext === 'MARKDOWN') return 'MD'
  return ext.slice(0, 4)
}

async function onReprocessDoc(doc) {
  const targetKbId = doc.kbId ?? expandedKbId.value
  if (!Number.isInteger(Number(targetKbId)) || Number(targetKbId) <= 0) {
    toast.warning('无法确定文档所属知识库')
    return
  }
  try {
    await reprocessDocument(doc.id)
    applyStatusToDoc(targetKbId, doc.id, {
      parseStatus: 'pending',
      chunkCount: 0,
      errorMsg: null,
    })
    ensureActiveTracking(doc.id, targetKbId, 'pending')
    beginPollingDoc(doc.id, targetKbId)
    toast.success('已提交重新处理')
  } catch {
    // 全局拦截器已 toast
  }
}

async function onDeleteDoc(doc) {
  if (!deleteEnabled) return
  const ok = await confirmDialog({
    title: '删除文档',
    message: `确定删除文档「${doc.filename}」？`,
    detail: '该文档及其分块、向量索引将被永久删除，此操作不可恢复。',
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  stopDocPolling(doc.id)
  if (activeDocId.value === doc.id) {
    activeDocId.value = null
    activeStatus.value = null
    advanceTrackingQueue()
  } else {
    pendingTrackQueue.value = pendingTrackQueue.value.filter((item) => item.docId !== doc.id)
  }
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
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  font-size: 13px;
  background: #fff;
  color: var(--text);
}

.upload-zone {
  border: 2px dashed #e2e8f0;
  border-radius: var(--radius-md);
  padding: 20px;
  text-align: center;
  background: #fafbfc;
  cursor: pointer;
  transition: all 0.15s ease;
}

.upload-zone-drag {
  border-color: var(--primary);
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

.upload-queue {
  display: grid;
  gap: 8px;
}

.upload-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 12px;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 10px 12px;
}

.upload-item.done {
  border-color: rgba(22, 163, 74, 0.28);
  background: #f0fdf4;
}

.upload-item.failed {
  border-color: rgba(220, 38, 38, 0.28);
  background: #fef2f2;
}

.upload-item-main {
  min-width: 0;
}

.upload-item-title {
  color: var(--slate);
  font-size: 13px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-item-meta {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
}

.upload-item-error {
  margin-top: 4px;
  color: #b91c1c;
  font-size: 12px;
}

.upload-progress-cell {
  display: grid;
  gap: 6px;
}

.upload-progress-text {
  color: var(--slate);
  font-size: 12px;
  font-weight: 800;
  text-align: right;
}

.upload-progress-bar {
  height: 7px;
  overflow: hidden;
  border-radius: 999px;
  background: #e2e8f0;
}

.upload-progress-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--primary);
  transition: width 0.16s ease;
}

.upload-retry-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.pipeline-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  padding: 16px 0;
  margin-bottom: 16px;
}

.pipeline-step {
  flex: 1;
  text-align: center;
  padding: 12px 8px;
  border-radius: var(--radius-sm);
  background: #f1f5f9;
  border: 2px solid #e2e8f0;
  transition: all 0.3s;
}

.step-icon { font-size: 18px; display: block; }
.step-label { display: block; font-weight: 600; font-size: 12px; margin-top: 2px; }
.step-sub { display: block; font-size: 10px; color: #94a3b8; margin-top: 2px; }
.pipe-arrow {
  color: #cbd5e1;
  font-size: 18px;
  padding: 0 4px;
  flex-shrink: 0;
  transition: color 0.3s;
}
.pipe-arrow.done { color: #10b981; }
.pipe-arrow.active { color: var(--primary); }
.pipe-arrow.failed { color: #ef4444; }

.pipeline-step.done { background: #f0fdf4; border-color: #10b981; }
.pipeline-step.active {
  background: #eff6ff;
  border-color: var(--primary);
  animation: pulse 1.5s infinite;
}
.pipeline-step.next { background: #f8fafc; border-color: #93c5fd; }
.pipeline-step.failed { background: #fef2f2; border-color: #ef4444; }

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(37, 99, 235,0.4); }
  50% { box-shadow: 0 0 0 6px rgba(37, 99, 235,0); }
}

.table-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
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
  font-weight: 600;
  font-size: 12px;
  text-transform: none;
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
  border-radius: var(--radius-full);
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

.link-action { cursor: pointer; font-size: 12px; color: var(--primary); margin-right: 10px; }
.link-action.danger { color: var(--red); margin-right: 0; }
.link-action.is-disabled { color: var(--text-muted); cursor: not-allowed; opacity: 0.55; }
.ro-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  background: var(--light);
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 1px 9px;
}
.kb-name-row { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.kb-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  border-radius: var(--radius-full);
  padding: 1px 9px;
  line-height: 1.7;
}
.kb-tag-org { background: #ede9fe; color: #5b21b6; }
.kb-tag-orgvis { background: #dbeafe; color: #1e40af; }
.kb-tag-pub { background: #dcfce7; color: #166534; }
.no-writable-hint {
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
  background: #fbfdff;
  color: var(--text-muted);
  font-size: 12.5px;
  padding: 14px 16px;
  text-align: center;
}

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
.docs-sub {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 11px;
}
.docs-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.recent-doc-list {
  display: grid;
  gap: 8px;
}

.recent-doc-card {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 10px;
}

.doc-type {
  width: 40px;
  height: 40px;
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

.doc-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.doc-name {
  color: var(--slate);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.45;
  word-break: break-word;
}

.doc-meta {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
}

.doc-error {
  margin-top: 4px;
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.5;
}

.doc-links {
  margin-top: 6px;
}

.docs-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.docs-table thead th {
  text-align: left;
  padding: 10px 12px;
  color: var(--slate);
  font-weight: 600;
  font-size: 12px;
  text-transform: none;
  border-bottom: 1px solid var(--border);
}
.docs-table tbody td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  color: var(--gray);
}
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
  border-radius: var(--radius-md);
  padding: 24px;
  width: min(420px, 92vw);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.15);
}
.modal-title { font-size: 16px; margin-bottom: 16px; }
.field { display: block; margin-bottom: 14px; }
.field span { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
.field-toggle .toggle-row { display: inline-flex; align-items: flex-start; gap: 10px; margin-bottom: 0; }
.field-toggle .toggle-row input[type="checkbox"] { width: 16px; height: 16px; margin-top: 1px; flex: 0 0 auto; cursor: pointer; }
.field-toggle .toggle-hint { display: inline; font-size: 12px; color: var(--text-muted); line-height: 1.5; }

.field-label-with-hint {
  display: inline-flex !important;
  align-items: center;
  gap: 4px;
}

.hint-icon {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 999px;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  user-select: none;
  outline: none;
  margin-bottom: 0 !important;
}
.hint-icon:hover,
.hint-icon:focus { color: var(--primary); }

.hint-popover {
  position: absolute;
  top: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  z-index: 100;
  width: 260px;
  padding: 10px 12px;
  background: #1e293b;
  color: #f1f5f9;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.25);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.55;
  text-align: left;
  white-space: normal;
  margin-bottom: 0 !important;
}
.hint-popover::before {
  content: '';
  position: absolute;
  top: -5px;
  left: 50%;
  transform: translateX(-50%) rotate(45deg);
  width: 10px;
  height: 10px;
  background: #1e293b;
}
.hint-popover strong {
  display: block;
  color: #fff;
  margin-bottom: 6px;
  font-size: 12px;
}
.hint-popover p {
  margin: 4px 0;
  color: #cbd5e1;
}
.hint-popover .hint-tip {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #60a5fa;
  font-size: 11.5px;
}
.field input, .field textarea {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  background: #fff;
  color: var(--text);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.field input:focus, .field textarea:focus, .field select:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}
.field.invalid input {
  border-color: #ef4444;
}
.field-error {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: #dc2626;
  line-height: 1.4;
}
.field select {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 32px 8px 10px;
  font-size: 13px;
  font-family: inherit;
  background: #fff url("data:image/svg+xml,%3Csvg width='12' height='8' viewBox='0 0 12 8' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1.5l5 5 5-5' stroke='%2364748b' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") no-repeat right 12px center;
  color: var(--text);
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.field select:hover {
  border-color: #94a3b8;
}
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
.edit-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .table-card {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    border-radius: var(--radius-sm);
  }

  .data-table {
    min-width: 0;
  }

  .data-table,
  .data-table thead,
  .data-table tbody,
  .data-table tr,
  .data-table td {
    display: block;
  }

  .data-table thead {
    display: none;
  }

  .data-table tbody {
    display: grid;
    gap: 10px;
    padding: 10px;
  }

  .data-table tr {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: #fff;
    overflow: hidden;
  }

  .data-table tbody td {
    display: grid;
    grid-template-columns: 74px minmax(0, 1fr);
    align-items: center;
    gap: 10px;
    border-bottom: 1px solid #eef2f7;
    padding: 8px 10px;
    word-break: break-word;
  }

  .data-table tbody td:last-child {
    border-bottom: none;
  }

  .data-table tbody td::before {
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 700;
  }

  .data-table tbody td:nth-child(1)::before { content: '知识库'; }
  .data-table tbody td:nth-child(2)::before { content: '文档'; }
  .data-table tbody td:nth-child(3)::before { content: 'Chunk'; }
  .data-table tbody td:nth-child(4)::before { content: '状态'; }
  .data-table tbody td:nth-child(5)::before { content: '创建'; }
  .data-table tbody td:nth-child(6)::before { content: '操作'; }

  .docs-row {
    border: none !important;
    background: transparent !important;
  }

  .docs-row > td {
    display: block !important;
    padding: 0 !important;
    border-bottom: none !important;
  }

  .docs-row > td::before {
    display: none;
  }

  .docs-panel {
    padding: 10px;
  }

  .docs-head {
    flex-direction: column;
    align-items: stretch;
  }

  .docs-actions {
    justify-content: flex-start;
  }

  .recent-doc-card {
    grid-template-columns: 1fr;
  }

  .doc-title-row {
    flex-direction: column;
    gap: 6px;
  }

  .docs-table {
    min-width: 0;
  }

  .docs-table,
  .docs-table thead,
  .docs-table tbody,
  .docs-table tr,
  .docs-table td {
    display: block;
  }

  .docs-table thead {
    display: none;
  }

  .docs-table tbody {
    display: grid;
    gap: 8px;
  }

  .docs-table tr {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: #fff;
    overflow: hidden;
  }

  .docs-table td {
    display: grid;
    grid-template-columns: 74px minmax(0, 1fr);
    align-items: center;
    gap: 10px;
    border-bottom: 1px solid #eef2f7;
    padding: 8px 10px;
    word-break: break-word;
  }

  .docs-table td:last-child {
    border-bottom: none;
  }

  .docs-table td::before {
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 700;
  }

  .docs-table td:nth-child(1)::before { content: '文件'; }
  .docs-table td:nth-child(2)::before { content: '大小'; }
  .docs-table td:nth-child(3)::before { content: '状态'; }
  .docs-table td:nth-child(4)::before { content: 'Chunk'; }
  .docs-table td:nth-child(5)::before { content: '上传'; }
  .docs-table td:nth-child(6)::before { content: '操作'; }

  .docs-table td[colspan] {
    display: block;
  }

  .docs-table td[colspan]::before {
    display: none;
  }

  .doc-filename {
    max-width: none;
    white-space: normal;
    word-break: break-word;
  }

  .doc-status-cell {
    flex-wrap: wrap;
  }

  .docs-table .link-action {
    display: inline-block;
    min-height: 28px;
    line-height: 28px;
  }

  .upload-settings {
    flex-direction: column;
    align-items: flex-start;
  }

  .top-toolbar,
  .toolbar-left,
  .select-label {
    width: 100%;
  }

  .toolbar-left {
    display: grid;
    grid-template-columns: 1fr 88px;
    gap: 8px;
  }

  .btn,
  .select {
    min-height: 40px;
  }

  .select-label {
    display: grid;
    grid-template-columns: 86px minmax(0, 1fr);
    align-items: center;
  }

  .upload-zone {
    padding: 18px 12px;
  }

  .upload-text {
    font-size: 12px;
    line-height: 1.5;
  }

  .pipeline-bar {
    flex-wrap: wrap;
    gap: 4px;
  }

  .pipeline-step {
    flex: 1 1 auto;
    min-width: 50px;
    padding: 8px 4px;
  }

  .pipe-arrow {
    font-size: 14px;
    padding: 0 2px;
  }

  .step-sub {
    display: none;
  }

  .toolbar-left {
    flex-wrap: wrap;
  }

  .edit-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .modal-actions {
    justify-content: stretch;
  }

  .modal-actions button {
    flex: 1;
  }
}
</style>
