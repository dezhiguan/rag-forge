<template>
  <div>
    <div class="page-body">
      <div class="top-toolbar">
        <div class="toolbar-left">
          <button v-if="canCreateKb" class="btn btn-primary" @click="openCreate">+ 创建知识库</button>
          <button class="btn btn-secondary" :disabled="loadingKb" @click="loadKbs">刷新</button>
          <button
            v-if="showAdminViewAll"
            class="btn btn-sm bg-breakglass"
            :class="{ on: adminViewAll }"
            :title="adminViewAll ? '正在以平台管理员身份查看全部知识库（已留审计）' : '平台管理员破玻璃：查看全部用户的知识库（将被审计）'"
            @click="toggleAdminViewAll"
          >
            <span class="bg-ic">🛡</span>{{ adminViewAll ? '查看全部·已留痕' : '查看全部 · 平台管理员' }}
          </button>
        </div>
        <div class="toolbar-right">
          <div class="kb-search" :class="{ has: kbKeyword }">
            <span class="kb-search-ico">🔍</span>
            <input
              v-model="kbKeyword"
              type="text"
              placeholder="搜索知识库名称"
              @keyup.enter="onSearchNow"
            />
            <span v-if="kbKeyword" class="kb-search-clear" @click="clearSearch">✕</span>
          </div>
        </div>
      </div>

      <div v-if="loadingKb" class="state-hint">
        <div class="state-icon">⏳</div>
        <div class="state-title">加载中...</div>
      </div>
      <div v-else-if="!kbList.length && kbKeyword" class="state-hint">
        <div class="state-icon">🔍</div>
        <div class="state-title">未找到匹配「{{ kbKeyword }}」的知识库</div>
        <div class="state-desc">换个关键词，或<span class="link-action" @click="clearSearch">清除搜索</span></div>
      </div>
      <div v-else-if="!kbList.length" class="state-hint">
        <div class="state-icon">📁</div>
        <div class="state-title">暂无知识库</div>
        <div class="state-desc">创建知识库并上传文档，开始构建检索能力</div>
      </div>

      <div v-else class="content">
        <div v-if="!uploadableKbs.length" class="no-writable-hint">
          <template v-if="canCreateKb">公共知识库为只读，你暂无可写入的知识库。请先「创建知识库」后再上传文档。</template>
          <template v-else>公共知识库为只读，你暂无可写入的知识库。请联系组织的所有者或管理员创建知识库，或为你分配写入权限。</template>
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
              accept=".pdf,.doc,.docx,.md,.markdown,.html,.htm,.txt,.png,.jpg,.jpeg,.gif,.webp,.zip,.tar.gz,.tgz,application/pdf,text/plain,image/*,application/zip,application/gzip,application/x-gzip,application/x-tar"
              @change="onFileChange"
            />
            <div class="upload-icon">📤</div>
            <div class="upload-text">
              拖拽文件上传 · 文档（PDF / Word / Markdown / HTML / TXT）+ 图片（PNG / JPG / GIF / WEBP，自动 OCR）+ 压缩包（ZIP / TAR.GZ，服务端自动展开）
            </div>
            <div class="upload-hint">单文件最大 50MB · 支持批量上传 · 压缩包一律上传后由服务端展开入库</div>
          </div>

          <div v-if="uploadItems.length" class="upload-queue">
            <article
              v-for="item in uploadItems"
              :key="item.id"
              class="upload-item"
              :class="{ failed: item.status === 'failed', done: item.status === 'done' }"
            >
              <div class="upload-item-main">
                <div class="upload-item-title">
                  {{ item.file.name }}
                  <span v-if="item.isArchive" class="archive-chip">压缩包</span>
                </div>
                <div class="upload-item-meta">
                  {{ formatBytes(item.file.size) }} · {{ phaseLabel(item.phase) }}
                  <template v-if="item.isArchive">
                    · <span class="archive-hint-text">{{ item.status === 'done' ? '展开中' : '将自动展开' }}</span>
                  </template>
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
            <span class="step-sub">多格式 + OCR</span>
          </div>
          <span class="pipe-arrow" :class="stepClass('parsing')">→</span>
          <div class="pipeline-step" :class="stepClass('chunking')">
            <span class="step-icon">✂️</span>
            <span class="step-label">分块</span>
            <span class="step-sub">多策略切分</span>
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
                            v-if="kb.visibility === 'PUBLIC'"
                            class="kb-tag kb-tag-pub"
                            data-test="kb-public-badge"
                          >公开</span>
                        </span>
                        <div v-if="kb.description" class="desc">{{ kb.description }}</div>
                        <span
                          v-if="kb.orgId || kb.visibility === 'ORG'"
                          class="kb-meta-row"
                        >
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
                        </span>
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
                      title="删除知识库"
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
                        <article
                          v-for="doc in docsMap[kb.id]?.list || []"
                          :key="doc.id"
                          class="recent-doc-card"
                          :class="{ 'is-archive': doc.isArchive }"
                        >
                          <div class="doc-type">{{ fileTypeLabel(doc.filename) }}</div>
                          <div class="doc-main">
                            <div class="doc-title-row">
                              <div class="doc-name">
                                <span
                                  v-if="doc.isArchive"
                                  class="archive-expander"
                                  role="button"
                                  :title="expandedDocIds[doc.id] ? '收起子文档' : '展开子文档'"
                                  @click.stop="toggleDocChildren(doc)"
                                >{{ expandedDocIds[doc.id] ? '▾' : '▸' }}</span>
                                {{ doc.filename }}
                                <span v-if="doc.isArchive" class="archive-chip">压缩包</span>
                              </div>

                              <!-- 压缩包容器：展开中 / 已展开徽标 / 失败 -->
                              <div
                                v-if="doc.isArchive"
                                class="doc-status-cell"
                                :title="isContainerFailed(doc.parseStatus) ? (doc.errorMsg || '展开失败') : undefined"
                              >
                                <span v-if="isContainerExpanding(doc.parseStatus)" class="status-icon spin">⟳</span>
                                <span v-else-if="isContainerExpanded(doc.parseStatus)" class="status-icon ok">✓</span>
                                <span v-else-if="isContainerFailed(doc.parseStatus)" class="status-icon fail">✗</span>
                                <span class="badge" :class="containerStatusClass(doc.parseStatus)">
                                  {{ containerStatusLabel(doc.parseStatus) }}
                                </span>
                              </div>

                              <!-- 普通文档状态 -->
                              <div
                                v-else
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

                            <!-- 压缩包容器：成功/失败/跳过 徽标 + 跳过明细 tooltip -->
                            <div v-if="doc.isArchive && doc.childrenSummary" class="archive-summary">
                              <span class="archive-badge ok">{{ doc.childrenSummary.completed ?? 0 }} 成功</span>
                              <span class="archive-badge fail">{{ doc.childrenSummary.failed ?? 0 }} 失败</span>
                              <span
                                class="archive-badge skip"
                                :class="{ 'has-detail': (doc.skippedEntries || []).length > 0 }"
                              >
                                {{ doc.childrenSummary.skipped ?? 0 }} 跳过
                                <span
                                  v-if="(doc.skippedEntries || []).length > 0"
                                  class="skip-popover"
                                >
                                  <strong>跳过明细（{{ doc.skippedEntries.length }} 条）</strong>
                                  <span
                                    v-for="(entry, idx) in doc.skippedEntries"
                                    :key="idx"
                                    class="skip-row"
                                  >
                                    <span class="skip-path">{{ entry.path }}</span>
                                    <span class="skip-reason">{{ skipReasonLabel(entry.reason) }}</span>
                                  </span>
                                </span>
                              </span>
                              <span
                                v-if="(doc.childrenSummary.processing || doc.childrenSummary.pending)"
                                class="archive-badge pending"
                              >{{ (doc.childrenSummary.processing ?? 0) + (doc.childrenSummary.pending ?? 0) }} 处理中</span>
                            </div>

                            <div class="doc-meta">
                              <template v-if="doc.isArchive">
                                {{ formatBytes(doc.fileSize) }} · 共 {{ doc.childrenSummary?.total ?? 0 }} 个文件 · {{ formatTime(doc.createdAt) }}
                              </template>
                              <template v-else>
                                {{ formatBytes(doc.fileSize) }} · v{{ doc.version ?? 1 }} · {{ doc.chunkCount ?? 0 }} chunks · {{ formatTime(doc.createdAt) }}
                              </template>
                            </div>

                            <div
                              v-if="doc.isArchive ? (isContainerFailed(doc.parseStatus) && doc.errorMsg) : (normalizeDocStatus(doc.parseStatus) === 'failed' && doc.errorMsg)"
                              class="doc-error"
                            >
                              {{ doc.errorMsg }}
                            </div>

                            <!-- 压缩包容器操作 -->
                            <div v-if="doc.isArchive" class="doc-links">
                              <span class="link-action" @click.stop="toggleDocChildren(doc)">
                                {{ expandedDocIds[doc.id] ? '收起子文档' : '查看子文档' }}
                              </span>
                              <span v-if="isContainerFailed(doc.parseStatus) && canWriteKb(kb)" class="link-action" @click.stop="onRetryContainer(doc)">重试展开</span>
                              <span
                                v-if="canWriteKb(kb)"
                                class="link-action danger"
                                title="删除压缩包及其全部子文档"
                                @click.stop="onDeleteDoc(doc)"
                              >
                                删除
                              </span>
                            </div>

                            <!-- 普通文档操作 -->
                            <div v-else class="doc-links">
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
                                title="删除文档"
                                @click.stop="onDeleteDoc(doc)"
                              >
                                删除
                              </span>
                            </div>

                            <!-- 子文档下钻 -->
                            <div v-if="doc.isArchive && expandedDocIds[doc.id]" class="archive-children">
                              <div v-if="childrenLoading[doc.id]" class="archive-children-hint">加载子文档中…</div>
                              <div v-else-if="(childrenMap[doc.id] || []).length === 0" class="archive-children-hint">
                                {{ isContainerExpanding(doc.parseStatus) ? '展开中，暂无已入库的子文档' : '该压缩包没有可入库的子文档' }}
                              </div>
                              <div v-else class="child-doc-list">
                                <div
                                  v-for="child in childrenMap[doc.id]"
                                  :key="child.id"
                                  class="child-doc-row"
                                >
                                  <span class="child-doc-name" @click.stop="goDoc(child.id, child.kbId ?? kb.id)">
                                    {{ child.archiveEntryPath || child.filename }}
                                  </span>
                                  <span class="child-doc-status">
                                    <span v-if="isProcessing(child.parseStatus)" class="status-icon spin">⟳</span>
                                    <span v-else-if="normalizeDocStatus(child.parseStatus) === 'completed'" class="status-icon ok">✓</span>
                                    <span v-else-if="normalizeDocStatus(child.parseStatus) === 'failed'" class="status-icon fail">✗</span>
                                    <span class="badge" :class="docStatusClass(child.parseStatus)">
                                      {{ docStatusLabel(child.parseStatus) }}
                                    </span>
                                  </span>
                                </div>
                              </div>
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
          <div v-if="kbTotalPages > 1" class="kb-pager">
            <span class="kb-pager-info">共 {{ kbTotal }} 个 · 第 {{ kbPage }}/{{ kbTotalPages }} 页</span>
            <div class="kb-pager-btns">
              <button class="pager-btn" :disabled="kbPage <= 1" @click="goPage(kbPage - 1)">上一页</button>
              <button class="pager-btn" :disabled="kbPage >= kbTotalPages" @click="goPage(kbPage + 1)">下一页</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
        <div class="modal">
          <h3 class="modal-title">创建知识库</h3>
          <div class="modal-body">
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
            <span class="toggle-head">
              <span class="toggle-title">多模态处理</span>
              <input type="checkbox" v-model="kbForm.imageModeOn" />
            </span>
            <span class="toggle-hint">
              开启后，HTML/PDF/Word 内嵌图与纯图片文档会走 OCR + 向量化入库；关闭只走文本管道。
            </span>
          </label>
          </div>
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
          <div class="modal-body">
          <label class="field">
            <span>名称 *</span>
            <input v-model="editForm.name" type="text" placeholder="知识库名称" />
          </label>
          <label class="field">
            <span>描述</span>
            <textarea v-model="editForm.description" rows="3" placeholder="可选" />
          </label>
          <label class="field">
            <span>可见性</span>
            <select v-model="editForm.visibility" data-test="kb-edit-visibility">
              <option v-for="opt in editVisibilityOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
            <small v-if="editForm.visibility !== editForm.originalVisibility" class="vis-hint">
              保存时将单独确认可见性变更（{{ VIS_LABELS[editForm.originalVisibility] }} → {{ VIS_LABELS[editForm.visibility] }}）
            </small>
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
            <span class="toggle-head">
              <span class="toggle-title">多模态处理</span>
              <input type="checkbox" v-model="editForm.imageModeOn" />
            </span>
            <span class="toggle-hint">
              开启后，HTML/PDF/Word 内嵌图与纯图片文档会走 OCR + 向量化入库；关闭只走文本管道。
            </span>
          </label>
          </div>
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
import { onMounted, onUnmounted, reactive, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createKb, deleteKb, listKb, listKbPaged, updateKb, getVisibilityImpact, changeVisibility } from '../api/kb'
import { listOrgs } from '../api/org'
import {
  deleteDocument,
  downloadDocument,
  getDocument,
  listDocumentChildren,
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
import { useOrg } from '../composables/useOrg'
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
const { isPlatform, current } = useOrg()
// 创建知识库权限：个人组织本人可建；团队组织仅 OWNER/ADMIN 可建（与后端 applyOwnership 的
// NOT_ORG_ADMIN 守卫一致）。MEMBER/平台视图不显示「创建知识库」按钮，避免点了才被 403。
const canCreateKb = computed(
  () => !isPlatform.value && (!!current.value?.personal || ['OWNER', 'ADMIN'].includes(current.value?.myRole)),
)
// 「查看全部知识库」是平台级越权：仅在「全平台视图」(破玻璃)下出现，与驾驶舱口径一致；
// 个人/团队组织上下文下不显示。
const showAdminViewAll = computed(() => ragRole.value === 'ADMIN' && isPlatform.value)
const adminViewAll = ref(false)
const adminOverrideReason = ref('')

const kbList = ref([]) // 当前页（服务端分页返回）
const allKbs = ref([]) // 全量（仅供上传下拉/默认上传目标用）
// 知识库列表分页（服务端分页，10 条/页）+ 名称模糊搜索
const KB_PAGE_SIZE = 10
const kbPage = ref(1)
const kbTotal = ref(0)
const kbKeyword = ref('')
const kbTotalPages = computed(() => Math.max(1, Math.ceil(kbTotal.value / KB_PAGE_SIZE)))
let kbSearchTimer = null
const loadingKb = ref(false)
const expandedKbId = ref(null)

const docsMap = reactive({})
const docsLoading = reactive({})

// ===== 压缩包容器（archive）相关状态 =====
// 子文档下钻缓存：containerId -> DocumentVO[]
const childrenMap = reactive({})
const childrenLoading = reactive({})
// 容器行展开态：containerId -> bool
const expandedDocIds = reactive({})
// 容器展开中轮询定时器：containerId -> intervalId
const containerTimers = new Map()

// 压缩包扩展名识别（前端仅用于 UI 标注，绝不解压）。
const ARCHIVE_EXT_RE = /\.(zip|tgz|tar\.gz)$/i
function isArchiveFile(name) {
  return ARCHIVE_EXT_RE.test(String(name || '').trim())
}

// 跳过原因中文映射（与后端 SkipReason.wireName 对齐）。
const SKIP_REASON_LABELS = {
  nested_archive: '嵌套压缩包',
  illegal_path: '非法路径',
  unsupported_type: '不支持的类型',
  oversize: '超大文件',
  register_failed: '入库失败',
}
function skipReasonLabel(reason) {
  return SKIP_REASON_LABELS[reason] || reason || '未知原因'
}

// 容器状态判定：parse_status 取值 PENDING/EXPANDING（展开中）、EXPANDED（终态成功，含部分成功）、FAILED（终态失败）。
function isContainerExpanding(status) {
  const s = normalizeDocStatus(status)
  return s === 'pending' || s === 'expanding' || s === 'processing'
}
function isContainerExpanded(status) {
  return normalizeDocStatus(status) === 'expanded'
}
function isContainerFailed(status) {
  return normalizeDocStatus(status) === 'failed'
}
function isContainerTerminal(status) {
  return isContainerExpanded(status) || isContainerFailed(status)
}
function containerStatusLabel(status) {
  const s = normalizeDocStatus(status)
  const map = { pending: '展开中', expanding: '展开中', processing: '展开中', expanded: '已展开', failed: '展开失败' }
  return map[s] || docStatusLabel(status)
}
function containerStatusClass(status) {
  if (isContainerExpanded(status)) return 'badge-green'
  if (isContainerFailed(status)) return 'badge-red'
  return 'badge-amber'
}

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
const uploadableKbs = computed(() => allKbs.value.filter(canWriteKb))

const showCreate = ref(false)
const showEdit = ref(false)
const submittingKb = ref(false)
const openHint = ref(null)

// 我加入的全部组织（含个人组织 personal/type=INDIVIDUAL）。
const allMyOrgs = ref([])
// 我作为 OWNER/ADMIN 的组织（可在其下建库）。一切皆组织：个人组织也在内，
// 统一以"组织：xxx"呈现；个人组织排第一位（默认项，组织多时更顺手）。
const manageableOrgs = computed(() =>
  allMyOrgs.value
    .filter((o) => o.myRole === 'OWNER' || o.myRole === 'ADMIN')
    .sort((a, b) => (b.personal ? 1 : 0) - (a.personal ? 1 : 0)),
)
// 我的个人组织（personal/type=INDIVIDUAL，恒存在且唯一），作为建库归属默认项。
const personalOrg = computed(() => allMyOrgs.value.find((o) => o.personal) || null)
async function loadManageableOrgs() {
  try {
    const res = await listOrgs()
    allMyOrgs.value = res?.data || res || []
  } catch (e) {
    allMyOrgs.value = []
  }
}
// 归属是否为「团队」组织——以组织的 personal 标记为准（个人组织 personal=true，
// 个人库可见性只能 PRIVATE/PUBLIC；团队库只能 PRIVATE/ORG）。orgId 为空＝个人（我自己）。
function isTeamOwnership(orgId) {
  if (orgId == null) return false
  const org = allMyOrgs.value.find((o) => o.id === orgId)
  return !!org && !org.personal
}
// 可见性选项随归属变化（模型 Y：移除全域公开）：个人库仅 PRIVATE；团队库 PRIVATE/ORG
const visibilityOptions = computed(() =>
  isTeamOwnership(kbForm.value.orgId)
    ? [
        { value: 'PRIVATE', label: '私有（仅组织管理员）' },
        { value: 'ORG', label: '组织可见（全体成员可读）' },
      ]
    : [{ value: 'PRIVATE', label: '私有（仅自己）' }]
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
  orgId: null,
  visibility: 'PRIVATE',
  originalVisibility: 'PRIVATE',
})
const VIS_LABELS = {
  PRIVATE: '私有（仅组织管理员）',
  ORG: '组织可见（全体成员可读）',
  PUBLIC: '公开（全平台所有组织可读）',
}
// 编辑模态可见性选项（模型 Y）：团队库 PRIVATE/ORG；个人库仅 PRIVATE。
const editVisibilityOptions = computed(() => {
  const vals = isTeamOwnership(editForm.value.orgId) ? ['PRIVATE', 'ORG'] : ['PRIVATE']
  return vals.map((v) => ({ value: v, label: VIS_LABELS[v] }))
})
const answerModes = ['OFF', 'PREVIEW', 'ON']
const answerModels = ['qwen-plus']

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
    const reason = await confirmDialog({
      title: '查看全部知识库',
      message: '这是平台管理员越权操作，将被审计留痕。请填写访问原因：',
      input: true,
      inputPlaceholder: '如：排查某用户入库失败',
      confirmText: '确认查看',
      variant: 'danger',
    })
    if (!reason || !String(reason).trim()) return
    adminOverrideReason.value = String(reason).trim()
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
    // 全量列表：仅用于上传下拉/默认上传目标（写权限库通常很少）
    const res = await listKb(adminViewAll.value ? adminOverrideReason.value : undefined)
    allKbs.value = res.data ?? []
    if ((!uploadKbId.value || !uploadableKbs.value.some((kb) => kb.id === uploadKbId.value)) && uploadableKbs.value.length) {
      uploadKbId.value = uploadableKbs.value[0].id
    }
    // 展示表格：服务端分页 + 搜索，回到第 1 页
    await loadKbPage(1)
  } finally {
    loadingKb.value = false
  }
}

/** 拉取当前页（服务端分页 + 名称模糊搜索）。 */
async function loadKbPage(page) {
  const res = await listKbPaged({
    page,
    size: KB_PAGE_SIZE,
    keyword: kbKeyword.value.trim() || undefined,
  })
  const data = res.data ?? {}
  kbList.value = data.list ?? []
  kbTotal.value = data.total ?? 0
  kbPage.value = data.page ?? page
}

function goPage(page) {
  const target = Math.max(1, Math.min(page, kbTotalPages.value))
  if (target === kbPage.value) return
  loadKbPage(target)
}

// 输入即筛（防抖 250ms），搜索回到第 1 页
watch(kbKeyword, () => {
  clearTimeout(kbSearchTimer)
  kbSearchTimer = setTimeout(() => loadKbPage(1), 250)
})
function onSearchNow() {
  clearTimeout(kbSearchTimer)
  loadKbPage(1)
}
function clearSearch() {
  kbKeyword.value = ''
  clearTimeout(kbSearchTimer)
  loadKbPage(1)
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

// ===== 容器展开轮询：复用 5s 节奏，拉容器详情刷新 childrenSummary，直到 EXPANDED/FAILED =====
const CONTAINER_POLL_INTERVAL_MS = 5000

function stopContainerPoll(docId) {
  const timer = containerTimers.get(docId)
  if (timer) {
    clearInterval(timer)
    containerTimers.delete(docId)
  }
}

function applyContainerDetail(kbId, docId, detail) {
  const list = docsMap[kbId]?.list
  if (!list || !detail) return
  const doc = list.find((d) => d.id === docId)
  if (!doc) return
  doc.parseStatus = detail.parseStatus
  if (detail.childrenSummary !== undefined) doc.childrenSummary = detail.childrenSummary
  if (detail.skippedEntries !== undefined) doc.skippedEntries = detail.skippedEntries
  doc.errorMsg = detail.errorMsg
  if (detail.isArchive !== undefined) doc.isArchive = detail.isArchive
}

function pollContainer(kbId, docId) {
  if (!docId || !kbId) return
  stopContainerPoll(docId)
  const tick = async () => {
    try {
      const res = await getDocument(docId)
      const detail = res.data
      applyContainerDetail(kbId, docId, detail)
      // 展开中同时刷新已下钻的子文档列表，让 UI 逐步显现入库结果
      if (expandedDocIds[docId]) await loadChildren(docId)
      if (isContainerTerminal(detail?.parseStatus)) {
        stopContainerPoll(docId)
        await loadKbs()
      }
    } catch {
      stopContainerPoll(docId)
    }
  }
  tick()
  containerTimers.set(docId, setInterval(tick, CONTAINER_POLL_INTERVAL_MS))
}

async function hydrateContainerDetail(kbId, docId) {
  try {
    const res = await getDocument(docId)
    applyContainerDetail(kbId, docId, res.data)
  } catch {
    // 静默：拉不到详情就维持列表原始展示
  }
}

async function loadChildren(containerId) {
  childrenLoading[containerId] = true
  try {
    const res = await listDocumentChildren(containerId)
    // 兼容后端返回 { list: [...] } 或直接数组
    childrenMap[containerId] = res.data?.list ?? res.data ?? []
  } catch {
    childrenMap[containerId] = childrenMap[containerId] ?? []
  } finally {
    childrenLoading[containerId] = false
  }
}

async function toggleDocChildren(doc) {
  const id = doc.id
  expandedDocIds[id] = !expandedDocIds[id]
  if (expandedDocIds[id] && !childrenMap[id]) {
    await loadChildren(id)
  }
}

async function onRetryContainer(doc) {
  const targetKbId = doc.kbId ?? expandedKbId.value
  if (!Number.isInteger(Number(targetKbId)) || Number(targetKbId) <= 0) {
    toast.warning('无法确定压缩包所属知识库')
    return
  }
  try {
    await reprocessDocument(doc.id)
    doc.parseStatus = 'EXPANDING'
    doc.errorMsg = null
    pollContainer(targetKbId, doc.id)
    toast.success('已提交重新展开')
  } catch {
    // 全局拦截器已 toast
  }
}

function watchProcessingDocs(kbId) {
  const list = docsMap[kbId]?.list ?? []
  for (const doc of list) {
    // 压缩包容器走独立的展开轮询（拉详情刷新 childrenSummary）
    if (doc.isArchive) {
      if (isContainerExpanding(doc.parseStatus)) {
        pollContainer(kbId, doc.id)
      } else if (doc.childrenSummary == null) {
        // 已终态但列表未带聚合：一次性拉详情补齐 childrenSummary / skippedEntries。
        hydrateContainerDetail(kbId, doc.id)
      }
      continue
    }
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

async function openCreate() {
  kbForm.value = {
    name: '',
    description: '',
    answerModel: '',
    imageModeOn: false,
    orgId: null,
    visibility: 'PRIVATE',
  }
  showCreate.value = true
  await loadManageableOrgs()
  // 默认归属＝个人组织（一切皆组织，沿用"默认建到自己名下"）
  kbForm.value.orgId = personalOrg.value?.id ?? manageableOrgs.value[0]?.id ?? null
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
  const vis = (kb.visibility || 'PRIVATE').toUpperCase()
  editForm.value = {
    id: kb.id,
    name: kb.name || '',
    description: kb.description || '',
    chunkSize: kb.chunkSize ?? null,
    chunkOverlap: kb.chunkOverlap ?? null,
    answerMode: kb.answerMode || 'ON',
    answerModel: kb.answerModel || '',
    imageModeOn: (kb.imageProcessingMode || '').toUpperCase() === 'ON',
    orgId: kb.orgId ?? null,
    visibility: vis,
    originalVisibility: vis,
  }
  showEdit.value = true
}

const VIS_RANK = { PRIVATE: 0, ORG: 1, PUBLIC: 2 }

/** 可见性变更确认流：放开→PUBLIC 需原因(P2)；从 PUBLIC 收紧需预检依赖(P1)。返回是否已处理成功。 */
async function applyVisibilityChange(id, oldVis, newVis) {
  const narrowing = VIS_RANK[newVis] < VIS_RANK[oldVis]
  // P2：放开到全平台 —— 强确认 + 必填原因
  if (newVis === 'PUBLIC') {
    const reason = await confirmDialog({
      title: '公开到全平台',
      message: `「${editForm.value.name}」将对平台所有组织可读。`,
      detail: '公开后全部历史文档立即对所有组织可见且可被检索，且很难真正收回。请填写公开原因（留审计）。',
      input: true,
      inputPlaceholder: '公开原因，如：作为平台公共知识共享',
      confirmText: '确认公开',
      cancelText: '取消',
      variant: 'danger',
    })
    if (!reason) return false
    await changeVisibility(id, { visibility: newVis, reason })
    return true
  }
  // P1：从 PUBLIC 收紧 —— 预检他组织依赖
  if (narrowing && oldVis === 'PUBLIC') {
    const impact = (await getVisibilityImpact(id, newVis))?.data || {}
    const keys = impact.crossOrgApiKeys || []
    if (keys.length > 0 || impact.evalDatasetCount > 0) {
      const lines = []
      if (keys.length > 0) {
        lines.push(`将断开 ${keys.length} 个他组织 API key：`)
        keys.slice(0, 5).forEach((k) => lines.push(`· ${k.orgName || '组织' + k.orgId} / ${k.keyName}`))
        if (keys.length > 5) lines.push(`…等共 ${keys.length} 个`)
      }
      if (impact.evalDatasetCount > 0) lines.push(`另有 ${impact.evalDatasetCount} 个评测数据集建在本库上。`)
      const ok = await confirmDialog({
        title: '收紧可见性会断开他组织依赖',
        message: `「${editForm.value.name}」收紧后，以下依赖将失去访问：`,
        detail: lines.join('\n'),
        confirmText: '仍然收紧',
        cancelText: '取消',
        variant: 'danger',
      })
      if (!ok) return false
      await changeVisibility(id, { visibility: newVis, force: true })
      return true
    }
  }
  // 其余（PRIVATE↔ORG 等同组织内变更）：直接确认
  const ok = await confirmDialog({
    title: '修改可见性',
    message: `将「${editForm.value.name}」的可见性改为「${VIS_LABELS[newVis]}」？`,
    confirmText: '确认',
    cancelText: '取消',
  })
  if (!ok) return false
  await changeVisibility(id, { visibility: newVis })
  return true
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
    // 可见性单独走确认流（权限+依赖预检+审计在后端）
    const newVis = editForm.value.visibility
    if (newVis && newVis !== editForm.value.originalVisibility) {
      const done = await applyVisibilityChange(editForm.value.id, editForm.value.originalVisibility, newVis)
      if (!done) {
        // 用户取消可见性变更：其余字段已保存，回退选择并保持弹窗
        editForm.value.visibility = editForm.value.originalVisibility
        await loadKbs()
        return
      }
      toast.success('可见性已更新')
    }
    showEdit.value = false
    await loadKbs()
  } finally {
    submittingKb.value = false
  }
}

async function onDeleteKb(kb) {
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
    // 压缩包标注：仅用于卡片文案（"压缩包 · 将自动展开 / 展开中"），前端绝不解压。
    isArchive: isArchiveFile(file?.name),
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
    if (docId && !item.isArchive) {
      // 普通文档：进入既有解析管道轮询。
      beginPollingDoc(docId, item.kbId)
    } else if (docId && item.isArchive) {
      // 压缩包：register 成功后容器进入 EXPANDING/PENDING，
      // 展开轮询由 handleFiles 末尾的 loadDocs → watchProcessingDocs 统一拉起。
      pollContainer(item.kbId, docId)
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
  stopContainerPoll(doc.id)
  delete childrenMap[doc.id]
  delete expandedDocIds[doc.id]
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

onUnmounted(() => {
  containerTimers.forEach((timer) => clearInterval(timer))
  containerTimers.clear()
})

onMounted(async () => {
  await loadKbs()
  // 默认全部合上，由用户点击展开（含第一个库）
  // 从驾驶舱「新建知识库」深链进入：自动打开创建弹窗，并清掉 query 防刷新重弹
  if (route.query.create) {
    openCreate()
    router.replace({ path: route.path })
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

/* 平台管理员「查看全部」破玻璃按钮：柔和红色胶囊，激活后填充红 */
.bg-breakglass {
  display: inline-flex; align-items: center; gap: 6px;
  color: #dc2626; background: #fef2f2; border: 1px solid #fecaca; font-weight: 600;
}
.bg-breakglass:hover { background: #fee2e2; border-color: #fca5a5; }
.bg-breakglass:focus, .bg-breakglass:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.18); }
.bg-breakglass.on { background: #dc2626; border-color: #dc2626; color: #fff; box-shadow: 0 1px 2px rgba(220, 38, 38, 0.3); }
.bg-breakglass.on:hover { background: #b91c1c; border-color: #b91c1c; }
.bg-breakglass .bg-ic { font-size: 12px; line-height: 1; }

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
  font-weight: 700;
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
  font-weight: 700;
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
  border-radius: 16px;
  overflow: hidden;
  background: #fff;
  box-shadow: var(--shadow-sm);
}
.kb-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
}
.kb-pager-info { font-size: 12.5px; color: var(--text-muted); }
.top-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.toolbar-right { margin-left: auto; }
.kb-search { position: relative; }
.kb-search input { height: 36px; width: 240px; padding: 0 32px; border: 1px solid var(--border); border-radius: 10px; font-size: 13px; background: #fff; outline: none; transition: .15s; }
.kb-search input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft, #eff4ff); }
.kb-search-ico { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--text-muted); font-size: 13px; pointer-events: none; }
.kb-search-clear { position: absolute; right: 9px; top: 50%; transform: translateY(-50%); color: var(--text-muted); cursor: pointer; font-size: 12px; }
.kb-search-clear:hover { color: var(--slate); }
.kb-pager-btns { display: flex; gap: 8px; }
.pager-btn {
  height: 30px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  color: var(--slate);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.pager-btn:hover:not(:disabled) { border-color: var(--primary-border); color: var(--primary); }
.pager-btn:disabled { opacity: 0.5; cursor: not-allowed; }

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
.kb-meta-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 4px; }
.kb-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  border-radius: var(--radius-full);
  padding: 1px 9px;
  line-height: 1.7;
}
.kb-tag-org { background: #f5f3ff; color: #7c3aed; }
.kb-tag-orgvis { background: #eef4ff; color: #2563eb; }
.kb-tag-pub { background: #ecfdf5; color: #15803d; }
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
.docs-title { font-size: 13px; color: var(--slate); font-weight: 700; }
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

/* ====== 压缩包容器（archive）UI ====== */
.recent-doc-card.is-archive {
  border-color: #c7d2fe;
  background: #fbfbff;
}

.archive-chip {
  display: inline-block;
  margin-left: 6px;
  font-size: 10px;
  font-weight: 700;
  color: #4338ca;
  background: #eef2ff;
  border: 1px solid #c7d2fe;
  border-radius: var(--radius-full);
  padding: 1px 8px;
  vertical-align: middle;
}

.archive-hint-text { color: var(--primary); font-weight: 600; }

.archive-expander {
  display: inline-block;
  margin-right: 4px;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 12px;
  user-select: none;
}
.archive-expander:hover { color: var(--primary); }

.archive-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.archive-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  border-radius: var(--radius-full);
  padding: 1px 9px;
  border: 1px solid transparent;
}
.archive-badge.ok { background: #dcfce7; color: #166534; border-color: rgba(22,101,52,0.2); }
.archive-badge.fail { background: #fee2e2; color: #991b1b; border-color: rgba(239,68,68,0.25); }
.archive-badge.skip { background: #f1f5f9; color: #475569; border-color: rgba(148,163,184,0.35); }
.archive-badge.pending { background: #fef3c7; color: #92400e; border-color: rgba(146,64,14,0.2); }

/* 跳过徽标 hover 明细弹层（复用页面既有 .hint-popover 深色气泡风格） */
.archive-badge.skip.has-detail { position: relative; cursor: help; }
.archive-badge.skip .skip-popover {
  display: none;
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 200;
  width: 280px;
  max-height: 240px;
  overflow-y: auto;
  padding: 10px 12px;
  background: #1e293b;
  color: #f1f5f9;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.25);
  font-weight: 400;
  text-align: left;
}
.archive-badge.skip.has-detail:hover .skip-popover { display: block; }
.skip-popover strong { display: block; color: #fff; font-size: 12px; margin-bottom: 6px; }
.skip-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 11.5px;
  line-height: 1.6;
  padding: 2px 0;
}
.skip-path { color: #cbd5e1; word-break: break-all; min-width: 0; }
.skip-reason { color: #fca5a5; flex: 0 0 auto; font-weight: 600; }

.archive-children {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.archive-children-hint { font-size: 12px; color: var(--text-muted); }

.child-doc-list { display: grid; gap: 6px; }
.child-doc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
}
.child-doc-name {
  min-width: 0;
  font-size: 12.5px;
  color: var(--primary);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.child-doc-name:hover { text-decoration: underline; }
.child-doc-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
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
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 14px;
  width: min(440px, 92vw);
  max-height: 88vh;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.22);
}
.modal-title {
  flex: 0 0 auto;
  font-size: 16px;
  font-weight: 700;
  color: var(--slate);
  margin: 0;
  padding: 18px 24px 14px;
  border-bottom: 1px solid var(--border);
}
.modal-body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 18px 24px 6px;
}
.field { display: block; margin-bottom: 16px; }
.field span { display: block; font-size: 12.5px; font-weight: 500; color: var(--slate); margin-bottom: 6px; }
.field-toggle .toggle-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.field-toggle .toggle-head input[type="checkbox"] { width: 16px; height: 16px; margin: 0; flex: 0 0 auto; cursor: pointer; }
.field-toggle .toggle-title { display: inline; font-size: 13px; color: var(--slate); font-weight: 500; margin: 0; }
.field-toggle .toggle-hint { display: block; font-size: 11px; color: var(--text-muted); line-height: 1.5; margin: 0; }

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
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 13px;
  font-family: inherit;
  background: #fff;
  color: var(--text);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.field input::placeholder, .field textarea::placeholder { color: #9aa6b6; }
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
  border-radius: 8px;
  padding: 9px 32px 9px 12px;
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
.modal-actions {
  flex: 0 0 auto;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin: 0;
  padding: 14px 24px;
  border-top: 1px solid var(--border);
  background: #fff;
}
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
