<template>
  <div>
    <div class="page-body">
      <div class="tab-bar">
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'datasets' }"
          @click="activeTab = 'datasets'"
        >
          评测数据集
        </button>
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'experiments' }"
          @click="activeTab = 'experiments'"
        >
          实验记录
        </button>
      </div>

      <template v-if="activeTab === 'datasets'">
        <div class="top-toolbar">
          <div class="toolbar-left">
            <button class="btn-primary" @click="openCreateDataset">+ 创建数据集</button>
            <button class="btn-ghost" :disabled="loadingDatasets" @click="loadDatasets">
              刷新
            </button>
          </div>
        </div>

        <div v-if="loadingDatasets" class="state-hint">加载中…</div>
        <div v-else-if="!datasets.length" class="state-hint">暂无评测数据集</div>

        <div v-else class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 36%;">名称</th>
                <th>题目数</th>
                <th>创建时间</th>
                <th style="width: 220px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="ds in datasets" :key="ds.id">
                <tr class="dataset-row" @click="toggleDataset(ds.id)">
                  <td>
                    <div class="dataset-cell">
                      <span class="expander">{{ expandedDatasetId === ds.id ? '▾' : '▸' }}</span>
                      <strong>{{ ds.name }}</strong>
                    </div>
                  </td>
                  <td>{{ ds.questionCount ?? 0 }}</td>
                  <td>{{ formatTime(ds.createdAt) }}</td>
                  <td>
                    <span class="link-action" @click.stop="toggleDataset(ds.id)">查看题目</span>
                    <span class="link-action muted" @click.stop>创建实验</span>
                    <span class="link-action danger" @click.stop="onDeleteDataset(ds)">删除</span>
                  </td>
                </tr>

                <tr v-if="expandedDatasetId === ds.id" class="questions-row">
                  <td colspan="4">
                    <div class="questions-panel">
                      <div class="questions-head">
                        <div class="questions-title">题目列表</div>
                        <div class="questions-actions">
                          <button class="btn-ghost btn-ghost-small" @click.stop="openAddQuestion(ds.id)">
                            + 添加题目
                          </button>
                          <button class="btn-ghost btn-ghost-small" @click.stop="openBatchImport(ds.id)">
                            批量导入
                          </button>
                          <button
                            class="btn-ghost btn-ghost-small"
                            :disabled="questionsLoading[ds.id]"
                            @click.stop="loadQuestions(ds.id, questionPage[ds.id] || 1)"
                          >
                            刷新
                          </button>
                        </div>
                      </div>

                      <table class="questions-table">
                        <thead>
                          <tr>
                            <th style="width: 50%;">问题</th>
                            <th>期望 Chunk IDs</th>
                            <th style="width: 80px;">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-if="!(questionsMap[ds.id]?.list || []).length">
                            <td colspan="3" class="empty-hint">暂无题目</td>
                          </tr>
                          <tr v-for="q in questionsMap[ds.id]?.list || []" :key="q.id">
                            <td class="question-text">{{ q.question }}</td>
                            <td class="chunk-ids">
                              {{ formatChunkIds(q.expectedChunkIds) }}
                            </td>
                            <td>
                              <span class="link-action danger" @click.stop="onDeleteQuestion(ds.id, q)">
                                删除
                              </span>
                            </td>
                          </tr>
                        </tbody>
                      </table>

                      <div
                        v-if="(questionsMap[ds.id]?.total || 0) > (questionsMap[ds.id]?.size || 20)"
                        class="pager"
                      >
                        <button
                          class="btn-ghost btn-ghost-small"
                          :disabled="(questionPage[ds.id] || 1) <= 1"
                          @click.stop="loadQuestions(ds.id, (questionPage[ds.id] || 1) - 1)"
                        >
                          上一页
                        </button>
                        <span class="pager-info">
                          第 {{ questionPage[ds.id] || 1 }} 页 / 共
                          {{ Math.ceil((questionsMap[ds.id]?.total || 0) / (questionsMap[ds.id]?.size || 20)) }} 页
                        </span>
                        <button
                          class="btn-ghost btn-ghost-small"
                          :disabled="
                            (questionPage[ds.id] || 1) >=
                            Math.ceil((questionsMap[ds.id]?.total || 0) / (questionsMap[ds.id]?.size || 20))
                          "
                          @click.stop="loadQuestions(ds.id, (questionPage[ds.id] || 1) + 1)"
                        >
                          下一页
                        </button>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else>
        <div class="eval-metrics">
          <div class="eval-card">
            <div class="card-label">评测问题数</div>
            <div class="card-value">100</div>
          </div>
          <div class="eval-card">
            <div class="card-label">Top3 命中率</div>
            <div class="card-value" style="color:#10b981;">89%</div>
          </div>
          <div class="eval-card">
            <div class="card-label">答案可信度</div>
            <div class="card-value" style="color:#10b981;">84%</div>
          </div>
          <div class="eval-card">
            <div class="card-label">平均耗时</div>
            <div class="card-value">1.9s</div>
          </div>
        </div>
        <div class="section-title">检索策略对比 · Top3 命中率</div>
        <div class="bar-chart">
          <div class="bar-col">
            <div class="bar" style="height:45px;opacity:0.4;"></div>
            <div class="bar-label">Naive</div><div class="bar-pct">62%</div>
          </div>
          <div class="bar-col">
            <div class="bar" style="height:55px;opacity:0.6;"></div>
            <div class="bar-label">BM25</div><div class="bar-pct">74%</div>
          </div>
          <div class="bar-col">
            <div class="bar" style="height:68px;opacity:0.8;"></div>
            <div class="bar-label">Hybrid</div><div class="bar-pct">85%</div>
          </div>
          <div class="bar-col">
            <div class="bar" style="height:75px;opacity:1;"></div>
            <div class="bar-label">Reranker</div><div class="bar-pct">89%</div>
          </div>
        </div>
        <div class="section-title">失败样本分析</div>
        <table class="data-table">
          <thead><tr><th>问题</th><th>失败原因</th><th>优化建议</th><th>操作</th></tr></thead>
          <tbody>
            <tr>
              <td>"AI工程师需要掌握哪些框架？"</td>
              <td><span class="badge badge-amber">召回太少</span> Top1 未命中</td>
              <td>提高 TopK，降低相似度阈值</td>
              <td><span class="link-action" @click="$router.push('/debug')">去调试 →</span></td>
            </tr>
            <tr>
              <td>"分布式系统面试一般怎么问？"</td>
              <td><span class="badge badge-red">Chunk切碎</span> 上下文不完整</td>
              <td>调整分块策略，增大重叠</td>
              <td><span class="link-action" @click="$router.push('/debug')">去调试 →</span></td>
            </tr>
            <tr>
              <td>"字节跳动的面试流程有几轮？"</td>
              <td><span class="badge badge-amber">文档缺失</span> 知识库无此信息</td>
              <td>补充面经文档</td>
              <td><span class="link-action" @click="$router.push('/knowledge')">去上传 →</span></td>
            </tr>
          </tbody>
        </table>
      </template>

      <div v-if="showCreateDataset" class="modal-mask" @click.self="showCreateDataset = false">
        <div class="modal">
          <h3 class="modal-title">创建评测数据集</h3>
          <label class="field">
            <span>名称 *</span>
            <input v-model="datasetForm.name" type="text" placeholder="例如：后端检索基准集" />
          </label>
          <label class="field">
            <span>关联知识库 *</span>
            <select v-model="datasetForm.kbId" class="select">
              <option :value="null" disabled>请选择知识库</option>
              <option v-for="kb in kbList" :key="kb.id" :value="kb.id">
                {{ kb.name }}
              </option>
            </select>
          </label>
          <div class="modal-actions">
            <button class="btn-ghost" @click="showCreateDataset = false">取消</button>
            <button class="btn-primary" :disabled="submittingDataset" @click="onCreateDataset">
              确定
            </button>
          </div>
        </div>
      </div>

      <div v-if="showAddQuestion" class="modal-mask" @click.self="showAddQuestion = false">
        <div class="modal">
          <h3 class="modal-title">添加题目</h3>
          <label class="field">
            <span>问题 *</span>
            <textarea v-model="questionForm.question" rows="3" placeholder="输入评测问题" />
          </label>
          <label class="field">
            <span>期望 Chunk IDs（逗号分隔）</span>
            <input v-model="questionForm.chunkIdsText" type="text" placeholder="例如：12, 45, 78" />
          </label>
          <div class="modal-actions">
            <button class="btn-ghost" @click="showAddQuestion = false">取消</button>
            <button class="btn-primary" :disabled="submittingQuestion" @click="onAddQuestion">
              确定
            </button>
          </div>
        </div>
      </div>

      <div v-if="showBatchImport" class="modal-mask" @click.self="showBatchImport = false">
        <div class="modal modal-wide">
          <h3 class="modal-title">批量导入题目</h3>
          <label class="field">
            <span>每行一个问题（期望 Chunk IDs 留空，后续可补充）</span>
            <textarea v-model="batchText" rows="8" placeholder="2026年后端开发需要掌握哪些AI技能？&#10;分布式系统面试一般怎么问？" />
          </label>
          <div class="modal-actions">
            <button class="btn-ghost" @click="showBatchImport = false">取消</button>
            <button class="btn-primary" :disabled="submittingBatch" @click="onBatchImport">
              导入
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  batchCreateEvalQuestions,
  createEvalDataset,
  createEvalQuestion,
  deleteEvalDataset,
  deleteEvalQuestion,
  listEvalDatasets,
  listEvalQuestions,
} from '../api/eval'
import { listKb } from '../api/kb'

const activeTab = ref('datasets')
const datasets = ref([])
const kbList = ref([])
const loadingDatasets = ref(false)
const expandedDatasetId = ref(null)

const questionsMap = reactive({})
const questionsLoading = reactive({})
const questionPage = reactive({})

const showCreateDataset = ref(false)
const submittingDataset = ref(false)
const datasetForm = ref({ name: '', kbId: null })

const showAddQuestion = ref(false)
const submittingQuestion = ref(false)
const addQuestionDatasetId = ref(null)
const questionForm = ref({ question: '', chunkIdsText: '' })

const showBatchImport = ref(false)
const submittingBatch = ref(false)
const batchDatasetId = ref(null)
const batchText = ref('')

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const res = await listEvalDatasets()
    datasets.value = res.data ?? []
  } finally {
    loadingDatasets.value = false
  }
}

async function loadQuestions(datasetId, page = 1) {
  questionsLoading[datasetId] = true
  questionPage[datasetId] = page
  try {
    const res = await listEvalQuestions(datasetId, page, 20)
    questionsMap[datasetId] = {
      list: res.data?.list ?? [],
      total: res.data?.total ?? 0,
      size: res.data?.size ?? 20,
    }
  } finally {
    questionsLoading[datasetId] = false
  }
}

async function toggleDataset(datasetId) {
  expandedDatasetId.value = expandedDatasetId.value === datasetId ? null : datasetId
  if (expandedDatasetId.value === datasetId && !questionsMap[datasetId]) {
    await loadQuestions(datasetId, 1)
  }
}

function openCreateDataset() {
  datasetForm.value = {
    name: '',
    kbId: kbList.value.length ? kbList.value[0].id : null,
  }
  showCreateDataset.value = true
}

async function onCreateDataset() {
  if (!datasetForm.value.name?.trim()) {
    alert('请填写数据集名称')
    return
  }
  if (!datasetForm.value.kbId) {
    alert('请选择关联知识库')
    return
  }
  submittingDataset.value = true
  try {
    await createEvalDataset({
      name: datasetForm.value.name.trim(),
      kbId: datasetForm.value.kbId,
    })
    showCreateDataset.value = false
    await loadDatasets()
  } finally {
    submittingDataset.value = false
  }
}

async function onDeleteDataset(ds) {
  if (!confirm(`确定删除数据集「${ds.name}」？关联题目将一并删除。`)) return
  await deleteEvalDataset(ds.id)
  if (expandedDatasetId.value === ds.id) expandedDatasetId.value = null
  delete questionsMap[ds.id]
  await loadDatasets()
}

function openAddQuestion(datasetId) {
  addQuestionDatasetId.value = datasetId
  questionForm.value = { question: '', chunkIdsText: '' }
  showAddQuestion.value = true
}

function parseChunkIdsText(text) {
  if (!text?.trim()) return []
  return text
    .split(/[,，\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map(Number)
    .filter((n) => !Number.isNaN(n))
}

async function onAddQuestion() {
  if (!questionForm.value.question?.trim()) {
    alert('请填写问题')
    return
  }
  submittingQuestion.value = true
  try {
    await createEvalQuestion(addQuestionDatasetId.value, {
      question: questionForm.value.question.trim(),
      expectedChunkIds: parseChunkIdsText(questionForm.value.chunkIdsText),
    })
    showAddQuestion.value = false
    await loadDatasets()
    await loadQuestions(addQuestionDatasetId.value, questionPage[addQuestionDatasetId.value] || 1)
  } finally {
    submittingQuestion.value = false
  }
}

function openBatchImport(datasetId) {
  batchDatasetId.value = datasetId
  batchText.value = ''
  showBatchImport.value = true
}

async function onBatchImport() {
  const lines = batchText.value
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  if (!lines.length) {
    alert('请至少输入一行问题')
    return
  }
  submittingBatch.value = true
  try {
    await batchCreateEvalQuestions(
      batchDatasetId.value,
      lines.map((question) => ({ question, expectedChunkIds: [] })),
    )
    showBatchImport.value = false
    await loadDatasets()
    await loadQuestions(batchDatasetId.value, 1)
  } finally {
    submittingBatch.value = false
  }
}

async function onDeleteQuestion(datasetId, question) {
  if (!confirm(`确定删除题目「${question.question.slice(0, 30)}…」？`)) return
  await deleteEvalQuestion(datasetId, question.id)
  await loadDatasets()
  await loadQuestions(datasetId, questionPage[datasetId] || 1)
}

function formatTime(iso) {
  if (!iso) return '-'
  return iso.toString().replace('T', ' ').slice(0, 19)
}

function formatChunkIds(ids) {
  if (!ids?.length) return '—'
  return ids.join(', ')
}

onMounted(async () => {
  try {
    const res = await listKb()
    kbList.value = res.data ?? []
  } catch {
    kbList.value = []
  }
  await loadDatasets()
})
</script>

<style scoped>
.page-body { padding: 20px 28px 32px; }

.tab-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.tab-btn {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  color: var(--slate);
}
.tab-btn.active {
  background: var(--blue);
  border-color: var(--blue);
  color: #fff;
}

.top-toolbar { margin-bottom: 12px; }
.toolbar-left { display: flex; gap: 10px; }

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
.btn-ghost-small { padding: 6px 10px; font-size: 12px; }

.state-hint {
  text-align: center;
  color: var(--text-muted);
  padding: 48px 0;
  font-size: 14px;
}

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

.dataset-row { cursor: pointer; background: #fff; }
.dataset-row:hover { background: #f8fafc; }
.dataset-cell { display: flex; align-items: center; gap: 10px; }
.expander { font-size: 16px; color: var(--text-muted); }

.link-action { cursor: pointer; font-size: 12px; color: var(--blue); margin-right: 10px; }
.link-action.danger { color: var(--red); margin-right: 0; }
.link-action.muted { color: var(--text-muted); cursor: default; }

.questions-row td { padding: 0; }
.questions-panel {
  padding: 16px;
  background: #fbfdff;
  border-top: 1px solid var(--border);
}
.questions-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.questions-title { font-size: 13px; color: var(--slate); font-weight: 800; }
.questions-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.questions-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.questions-table thead th {
  text-align: left;
  padding: 10px 12px;
  color: var(--slate);
  font-weight: 700;
  font-size: 11px;
  text-transform: uppercase;
  border-bottom: 1px solid var(--border);
}
.questions-table tbody td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  color: var(--gray);
}
.question-text { line-height: 1.5; word-break: break-word; }
.chunk-ids { font-family: 'SF Mono', Monaco, monospace; font-size: 12px; }
.empty-hint { color: var(--text-muted); text-align: center; padding: 20px 12px; }

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}
.pager-info { font-size: 12px; color: var(--text-muted); }

.eval-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 24px; }
.eval-card { background: var(--navy); border-radius: 10px; padding: 16px; color: #fff; }
.card-label { font-size: 10px; opacity: 0.5; text-transform: uppercase; letter-spacing: 1px; }
.card-value { font-size: 28px; font-weight: 700; letter-spacing: -1px; }
.section-title { font-weight: 600; font-size: 13px; margin-bottom: 12px; color: var(--slate); }
.bar-chart { display: flex; align-items: flex-end; justify-content: center; gap: 20px; height: 90px; margin-bottom: 24px; padding: 0 8px; }
.bar-col { text-align: center; }
.bar { width: 44px; background: var(--blue); border-radius: 3px 3px 0 0; }
.bar-label { font-size: 10px; font-weight: 600; color: var(--slate); margin-top: 4px; }
.bar-pct { font-size: 10px; color: var(--blue); font-weight: 600; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.badge-amber { background: #fef3c7; color: #92400e; }
.badge-red { background: #fee2e2; color: #991b1b; }

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
.modal-wide { width: min(520px, 92vw); }
.modal-title { font-size: 16px; margin-bottom: 16px; }
.field { display: block; margin-bottom: 14px; }
.field span { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
.field input, .field textarea, .select {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  background: #fff;
  color: var(--text);
}
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
</style>
