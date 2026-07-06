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
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'chunkerAb' }"
          @click="activeTab = 'chunkerAb'"
        >
          分块 A/B
        </button>
      </div>

      <template v-if="activeTab === 'datasets'">
        <!-- 统计卡片 -->
        <div class="summary-cards" v-if="datasets.length">
          <div class="summary-card">
            <div class="summary-num">{{ datasets.length }}</div>
            <div class="summary-label">数据集</div>
          </div>
          <div class="summary-card">
            <div class="summary-num">{{ totalQuestions }}</div>
            <div class="summary-label">总题目数</div>
          </div>
          <div class="summary-card">
            <div class="summary-num">{{ totalExperiments }}</div>
            <div class="summary-label">实验记录</div>
          </div>
        </div>

        <div v-if="datasets.length" class="top-toolbar">
          <div class="toolbar-left">
            <button class="btn btn-primary" @click="openCreateDataset">+ 创建数据集</button>
            <button class="btn-ghost btn-sm" :disabled="loadingDatasets" @click="loadDatasets">刷新</button>
          </div>
        </div>

        <div v-if="loadingDatasets" class="state-hint">
          <div class="state-icon">⏳</div>
          <div class="state-title">加载中...</div>
        </div>
        <div v-else-if="!datasets.length" class="eval-onboard">
          <div class="onboard-hero">
            <div class="onboard-icon">🧪</div>
            <div class="onboard-title">评测实验室</div>
            <div class="onboard-desc">
              用真实数据检验不同检索策略的效果——<br>向量检索、关键词检索、混合检索、全链路 Reranker，哪个更准？
            </div>
          </div>
          <div class="onboard-steps">
            <div class="onboard-step">
              <div class="step-num">1</div>
              <div class="step-body">
                <div class="step-title">上传文档到知识库</div>
                <div class="step-desc">将你的简历 PDF 上传，系统自动解析分块并生成向量索引</div>
              </div>
            </div>
            <div class="onboard-step">
              <div class="step-num">2</div>
              <div class="step-body">
                <div class="step-title">创建评测数据集</div>
                <div class="step-desc">添加问题 + 标注期望命中的 Chunk；快速体验数据集属于自动弱标注，后续建议人工复核</div>
              </div>
            </div>
            <div class="onboard-step">
              <div class="step-num">3</div>
              <div class="step-body">
                <div class="step-title">运行实验，对比策略</div>
                <div class="step-desc">一键对比五种检索策略的 Top1/Top3/MRR，找到更稳定的检索方案</div>
              </div>
            </div>
          </div>
          <div class="onboard-actions">
            <button class="btn btn-primary" @click="openCreateDataset">+ 创建数据集</button>
            <button
              class="btn-accent"
              @click="kbList.length ? openQuickStart() : goToKnowledge()"
            >
              {{ kbList.length ? '⚡ 快速体验（自动弱标注）' : '先创建知识库后体验' }}
            </button>
          </div>
        </div>

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
                      <div class="dataset-title-wrap">
                        <strong>{{ ds.name }}</strong>
                        <div class="dataset-kb-line">
                          <span class="dataset-kb-label">关联知识库</span>
                          <span>{{ kbNameOf(ds) }}</span>
                        </div>
                      </div>
                    </div>
                  </td>
                  <td>{{ ds.questionCount ?? 0 }}</td>
                  <td>{{ formatTime(ds.createdAt) }}</td>
                  <td class="actions-cell">
                    <button class="btn-outline-sm" @click.stop="openRunExperiment(ds.id)">创建实验</button>
                    <span
                      class="link-action"
                      :class="ds.locked ? 'action-locked' : 'danger-subtle'"
                      :title="ds.locked ? '冻结的基线评测集，已锁定不可删除' : ''"
                      @click.stop="ds.locked || onDeleteDataset(ds)"
                    >{{ ds.locked ? '🔒 已锁定' : '删除' }}</span>
                  </td>
                </tr>

                <tr v-if="expandedDatasetId === ds.id" class="questions-row">
                  <td colspan="4">
                    <div class="questions-panel">
                      <div class="questions-head">
                        <div class="questions-title">题目列表</div>
                        <div class="questions-actions">
                          <button class="btn-outline-sm" @click.stop="openAddQuestion(ds.id)">+ 添加题目</button>
                          <span class="link-action" @click.stop="openBatchImport(ds.id)">批量导入</span>
                        </div>
                      </div>

                      <table class="questions-table">
                        <thead>
                          <tr>
                            <th style="width: 34%;">问题</th>
                            <th>期望标注</th>
                            <th style="width: 110px;">Golden Set（黄金集）</th>
                            <th style="width: 150px;">Tags（标签）</th>
                            <th style="width: 80px;">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-if="!(questionsMap[ds.id]?.list || []).length">
                            <td colspan="5">
                              <div class="state-hint" style="padding:24px 0">
                                <div class="state-icon">📝</div>
                                <div class="state-desc">点击「添加题目」或「批量导入」</div>
                              </div>
                            </td>
                          </tr>
                          <tr v-for="q in questionsMap[ds.id]?.list || []" :key="q.id">
                            <td class="question-text">
                              <span v-if="q.isCore" class="core-badge" title="平台级黄金集核心题·质量基线已冻结，不可编辑/删除">🔒 核心</span>
                              {{ q.question }}
                            </td>
                            <td class="chunk-ids">
                              <div :title="formatChunkIds(q.expectedChunkIds)">Chunk: {{ formatChunkIds(q.expectedChunkIds) }}</div>
                              <div v-if="(q.expectedTextSnippets || []).length" class="text-snippet-line">
                                文本: {{ formatTextSnippets(q.expectedTextSnippets) }}
                              </div>
                            </td>
                            <td>
                              <label class="golden-toggle" @click.stop>
                                <input
                                  type="checkbox"
                                  :checked="q.judgeEnabled"
                                  :disabled="q.isCore"
                                  @change="onToggleJudgeEnabled(ds.id, q, $event.target.checked)"
                                >
                                <span>{{ q.judgeEnabled ? '启用' : '关闭' }}</span>
                              </label>
                            </td>
                            <td>
                              <select
                                class="tag-select"
                                multiple
                                :value="q.judgeTags || []"
                                :disabled="q.isCore"
                                @click.stop
                                @change="onJudgeTagsChange(ds.id, q, $event)"
                              >
                                <option v-for="tag in judgeTagOptions" :key="tag.value" :value="tag.value">{{ tag.label }}</option>
                              </select>
                            </td>
                            <td>
                              <template v-if="q.isCore">
                                <span class="link-action is-locked" title="平台级黄金集核心题，已冻结">🔒 已锁定</span>
                              </template>
                              <template v-else>
                                <span class="link-action" @click.stop="openEditQuestion(ds.id, q)">
                                  编辑
                                </span>
                                <span class="link-action danger" @click.stop="onDeleteQuestion(ds.id, q)">
                                  删除
                                </span>
                              </template>
                            </td>
                          </tr>
                        </tbody>
                      </table>

                      <div
                        v-if="(questionsMap[ds.id]?.total || 0) > (questionsMap[ds.id]?.size || 20)"
                        class="pager"
                      >
                        <button
                          class="btn btn-secondary btn-sm"
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
                          class="btn btn-secondary btn-sm"
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

      <template v-else-if="activeTab === 'experiments'">
        <!-- 概览卡片 -->
        <div class="summary-cards" v-if="experiments.length">
          <div class="summary-card">
            <div class="summary-num">{{ expTotal }}</div>
            <div class="summary-label">实验总数</div>
          </div>
          <div class="summary-card">
            <div class="summary-num accent-green">{{ bestTop3Rate }}</div>
            <div class="summary-label">最佳 Top3</div>
          </div>
          <div class="summary-card">
            <div class="summary-num">{{ avgMrr }}</div>
            <div class="summary-label">平均 MRR</div>
          </div>
          <div class="summary-card">
            <div class="summary-num">{{ avgLatency }}ms</div>
            <div class="summary-label">平均延迟</div>
          </div>
        </div>

        <div class="top-toolbar">
          <div class="toolbar-left">
            <button class="btn btn-primary" @click="openRunExperiment">+ 运行新实验</button>
            <button class="btn-ghost btn-sm" :disabled="loadingExperiments" @click="loadExperiments(expPage)">刷新</button>
          </div>
          <div class="toolbar-right">
            <div class="kb-search" :class="{ has: expKeyword }">
              <span class="kb-search-ico">🔍</span>
              <input
                v-model="expKeyword"
                type="text"
                placeholder="搜索数据集名称"
                @input="onExpSearchInput"
              />
              <span v-if="expKeyword" class="kb-search-clear" @click="expKeyword = ''; loadExperiments(1)">✕</span>
            </div>
          </div>
        </div>

        <div v-if="loadingExperiments" class="state-hint">
          <div class="state-icon">⏳</div>
          <div class="state-title">加载中...</div>
        </div>
        <div v-else-if="!experiments.length" class="state-hint">
          <div class="state-icon">🧪</div>
          <div class="state-title">暂无实验记录</div>
          <div class="state-desc">选择一个数据集，运行实验对比不同检索策略</div>
        </div>
        <div v-else class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>数据集</th>
                <th>策略</th>
                <th>题目数</th>
                <th>Top1</th>
                <th>Top3</th>
                <th>MRR <span style="color:var(--text-muted);font-weight:400;font-size:10px">平均倒数排名</span></th>
                <th>平均耗时</th>
                <th>时间</th>
                <th style="width: 130px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="exp in experiments" :key="exp.id" class="experiment-row">
                <td>{{ exp.datasetName || `#${exp.datasetId}` }}</td>
                <td><span class="strategy-badge" :class="`strategy-${exp.strategy}`">{{ strategyLabelMap[exp.strategy] || exp.strategy }}</span></td>
                <td>{{ exp.totalQuestions ?? 0 }}</td>
                <td class="metric-cell">{{ formatRate(exp.top1HitRate) }}</td>
                <td class="metric-cell metric-accent">{{ formatRate(exp.top3HitRate) }}</td>
                <td class="metric-cell">{{ formatMrr(exp.mrr) }}</td>
                <td>{{ exp.avgLatencyMs ?? 0 }}ms</td>
                <td>{{ formatTime(exp.createdAt) }}</td>
                <td class="actions-cell">
                  <span class="link-action" @click="openExperimentDetail(exp.id)">详情</span>
                  <span class="link-action danger-subtle" @click="onDeleteExperiment(exp)">删除</span>
                </td>
              </tr>
            </tbody>
          </table>
          <Pager
            v-if="experiments.length"
            :total="expTotal"
            :page="expPage"
            :size="expSize"
            unit="条"
            @update:page="loadExperiments"
            @update:size="onExpSize"
          />
        </div>
      </template>

      <template v-else>
        <div class="ab-panel">
          <div class="ab-form">
            <label class="field">
              <span>评测数据集</span>
              <select v-model="chunkerAbForm.evalDatasetId" class="select">
                <option :value="null" disabled>请选择数据集</option>
                <option v-for="ds in datasets" :key="ds.id" :value="ds.id">
                  {{ ds.name }} - {{ kbNameOf(ds) }}
                </option>
              </select>
            </label>
            <div class="strategy-checks">
              <label v-for="item in chunkerStrategyOptions" :key="item.value" class="check-item">
                <input v-model="chunkerAbForm.strategies" type="checkbox" :value="item.value">
                <span>{{ item.label }}<span class="field-hint">（{{ item.hint }}）</span></span>
              </label>
            </div>
            <div class="ab-param-grid">
              <label class="field">
                <span>chunkSize<span class="field-hint">（分块大小）</span></span>
                <input v-model.number="chunkerAbForm.params.chunkSize" type="number" min="100" max="2000" />
              </label>
              <label class="field">
                <span>overlap<span class="field-hint">（重叠字符数）</span></span>
                <input v-model.number="chunkerAbForm.params.overlap" type="number" min="0" max="500" />
              </label>
              <label class="field">
                <span>simThreshold<span class="field-hint">（语义相似度阈值）</span></span>
                <input v-model.number="chunkerAbForm.params.simThreshold" type="number" min="0.1" max="0.95" step="0.05" />
              </label>
            </div>
            <button class="btn btn-primary" :disabled="runningChunkerAb" @click="onRunChunkerAb">
              {{ runningChunkerAb ? '运行中…' : '运行分块 A/B' }}
            </button>
          </div>

          <div v-if="chunkerAbResults.length" class="table-card">
            <table class="data-table">
              <thead>
                <tr>
                  <th>分块策略</th>
                  <th>Top1</th>
                  <th>MRR</th>
                  <th>平均 chunk 长度</th>
                  <th>总 chunk 数</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in chunkerAbResults" :key="item.strategy">
                  <td>
                    <span class="strategy-badge">{{ chunkerStrategyLabel(item.strategy) }}</span>
                  </td>
                  <td v-if="item.note" colspan="4" class="skip-note">跳过 · {{ item.note }}</td>
                  <template v-else>
                    <td class="metric-cell">{{ formatRate(item.top1) }}</td>
                    <td class="metric-cell metric-accent">{{ formatMrr(item.mrr) }}</td>
                    <td>{{ item.avgChunkLen }}</td>
                    <td>{{ item.totalChunks }}</td>
                  </template>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="state-hint">
            <div class="state-title">暂无分块试验结果</div>
            <div class="state-desc">选择同一个评测集，对比不同分块策略的 Top1/MRR 和 chunk 规模</div>
          </div>
        </div>
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
            <button class="btn btn-secondary" @click="showCreateDataset = false">取消</button>
            <button class="btn btn-primary" :disabled="submittingDataset" @click="onCreateDataset">
              确定
            </button>
          </div>
        </div>
      </div>

      <div v-if="showAddQuestion" class="modal-mask" @click.self="showAddQuestion = false">
        <div class="modal modal-question">
          <h3 class="modal-title">{{ editingQuestionId ? '编辑题目' : '添加题目' }}</h3>
          <template v-if="addQuestionStep === 1">
            <label class="field">
              <span>问题 *</span>
              <textarea v-model="questionForm.question" rows="3" placeholder="输入评测问题" />
            </label>
            <label class="field">
              <span>期望文本片段（可选，每行一个）</span>
              <textarea v-model="expectedTextSnippetsInput" rows="3" placeholder="用于硬覆盖后稳定评测，例如文档中的关键原文片段" />
            </label>
            <div class="modal-actions">
              <button class="btn btn-secondary" @click="showAddQuestion = false">取消</button>
              <button class="btn btn-primary" :disabled="searchingCandidates" @click="onSearchCandidates">
                {{ searchingCandidates ? '检索中…' : '🔍 检索候选 Chunk' }}
              </button>
            </div>
          </template>

          <template v-else>
            <div class="field">
              <span>问题</span>
              <div class="question-preview">{{ questionForm.question }}</div>
            </div>
            <div class="selected-count">
              已选 {{ selectedChunkIds.length }} 个 chunk
              <span v-if="parsedExpectedTextSnippets.length"> · {{ parsedExpectedTextSnippets.length }} 个文本片段</span>
            </div>
            <div v-if="!candidateChunks.length" class="empty-hint">未检索到候选 Chunk</div>
            <div v-else class="candidate-list">
              <label
                v-for="item in candidateChunks"
                :key="item.chunkId ?? `${item.filename}-${item.chunkIndex}`"
                class="candidate-item"
              >
                <input
                  v-if="item.chunkId != null"
                  v-model="selectedChunkIds"
                  :value="item.chunkId"
                  type="checkbox"
                >
                <div class="candidate-body">
                  <div class="candidate-head">
                    <span>{{ item.filename }} #{{ item.chunkIndex }}</span>
                    <span class="candidate-score">{{ Number(item.score || 0).toFixed(4) }}</span>
                  </div>
                  <div class="candidate-content" :title="item.content">{{ candidatePreview(item.content) }}</div>
                </div>
              </label>
            </div>
            <div class="modal-actions">
              <button class="btn btn-secondary" @click="addQuestionStep = 1">上一步</button>
              <button class="btn btn-primary" :disabled="submittingQuestion" @click="onAddQuestion">
                {{ submittingQuestion ? '保存中…' : editingQuestionId ? '保存标注' : '确认保存' }}
              </button>
            </div>
          </template>
        </div>
      </div>

      <div v-if="showRunExperiment" class="modal-mask" @click.self="showRunExperiment = false">
        <div class="modal">
          <h3 class="modal-title">运行实验</h3>
          <label class="field">
            <span>数据集 *</span>
            <select v-model="runForm.datasetId" class="select">
              <option :value="null" disabled>请选择数据集</option>
              <option v-for="ds in datasets" :key="ds.id" :value="ds.id">
                {{ ds.name }} - 知识库：{{ kbNameOf(ds) }}（{{ ds.questionCount ?? 0 }}题）
              </option>
            </select>
          </label>
          <label class="field">
            <span>策略</span>
            <select v-model="runForm.strategy" class="select" :disabled="runForm.ablation">
              <option value="vector">向量检索（vector）</option>
              <option value="keyword">关键词检索 BM25（keyword）</option>
              <option value="hybrid">混合检索（hybrid）</option>
              <option value="full">全链路（full）</option>
              <option value="rewrite">Query 改写（rewrite）</option>
            </select>
          </label>

          <!-- 消融实验 Toggle -->
          <div class="ablation-toggle" @click="runForm.ablation = !runForm.ablation">
            <div class="toggle-track" :class="{ on: runForm.ablation }">
              <div class="toggle-thumb"></div>
            </div>
            <div class="toggle-body">
              <div class="toggle-label">消融实验</div>
              <div class="toggle-desc">自动对比向量检索 / 关键词检索 / 混合检索 / 全链路 / Query 改写 五种策略</div>
            </div>
          </div>

          <!-- 高级选项（单策略时可用） -->
          <div v-if="!runForm.ablation" class="advanced-toggle" @click="showAdvanced = !showAdvanced">
            ⚙ 高级选项 {{ showAdvanced ? '▾' : '▸' }}
          </div>
          <template v-if="!runForm.ablation && showAdvanced">
            <label class="field">
              <span>TopK</span>
              <input v-model.number="runForm.topK" type="number" min="1" max="50" />
            </label>
            <label class="field">
              <span>向量权重（混合检索/全链路）</span>
              <input v-model.number="runForm.vectorWeight" type="number" min="0" max="1" step="0.05" />
            </label>
          </template>

          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showRunExperiment = false">取消</button>
            <button class="btn btn-primary" :disabled="runningExperiment" @click="onRunExperiment">
              {{ runningExperiment ? '运行中…' : '开始运行' }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="showExperimentDetail && experimentDetail" class="modal-mask" @click.self="showExperimentDetail = false">
        <div class="modal modal-detail">
          <h3 class="modal-title">实验详情 · {{ strategyLabelMap[experimentDetail.strategy] || experimentDetail.strategy }}</h3>
          <div class="eval-metrics">
            <div class="eval-card">
              <div class="card-label">题目数</div>
              <div class="card-value">{{ experimentDetail.totalQuestions ?? 0 }}</div>
            </div>
            <div class="eval-card">
              <div class="card-label">Top1 命中率</div>
              <div class="card-value">{{ formatRate(experimentDetail.top1HitRate) }}</div>
            </div>
            <div class="eval-card">
              <div class="card-label">Top3 命中率</div>
              <div class="card-value">{{ formatRate(experimentDetail.top3HitRate) }}</div>
            </div>
            <div class="eval-card">
              <div class="card-label">MRR 平均倒数排名</div>
              <div class="card-value">{{ formatMrr(experimentDetail.mrr) }}</div>
            </div>
            <div class="eval-card">
              <div class="card-label">平均耗时</div>
              <div class="card-value">{{ experimentDetail.avgLatencyMs ?? 0 }}ms</div>
            </div>
          </div>

          <!-- 每道题检索结果 -->
          <div class="section-title">
            逐题检索结果
            <span style="font-weight:400;font-size:11px;color:var(--text-muted);margin-left:8px;">
              期望 Chunk = 人工或弱标注的标准 Chunk，实际召回 = 检索策略返回的 Top-K
            </span>
          </div>
          <div v-if="(experimentDetail.results || []).length" class="per-question-table">
            <table class="data-table">
              <thead>
                <tr>
                  <th style="width:30%;">问题</th>
                  <th>期望 Chunk</th>
                  <th>实际召回 Chunk</th>
                  <th>命中位置</th>
                  <th>Top1</th>
                  <th>Top3</th>
                  <th>MRR</th>
                  <th>耗时</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in experimentDetail.results" :key="r.questionId" :class="{ 'row-fail': r.failureReason }">
                  <td class="question-cell" :title="r.question">{{ r.question }}</td>
                  <td class="chunk-cell">
                    <div v-if="(r.expectedChunks || []).length" class="chunk-preview-list">
                      <div
                        v-for="chunk in (r.expectedChunks || []).slice(0, 3)"
                        :key="chunk.chunkId"
                        class="chunk-preview-item"
                        @click="openChunkDetail(chunk, '期望 Chunk')"
                      >
                        <div class="chunk-preview-head">
                          <span class="chunk-preview-id">#{{ chunk.chunkId }}</span>
                          <span class="chunk-preview-meta">chunk {{ chunk.chunkIndex ?? '-' }}</span>
                        </div>
                        <div class="chunk-preview-text" :title="chunk.content">{{ previewChunk(chunk.content) }}</div>
                      </div>
                      <div v-if="(r.expectedChunks || []).length > 3" class="chunk-preview-more">+{{ (r.expectedChunks || []).length - 3 }} 条</div>
                    </div>
                    <span v-else :title="formatChunkIds(r.expectedChunkIds)">{{ formatChunkIds(r.expectedChunkIds) }}</span>
                    <div v-if="(r.expectedTextSnippets || []).length" class="text-match-list">
                      <div
                        v-for="match in r.expectedTextMatches || []"
                        :key="match.textSnippet"
                        :class="['text-match-item', match.matched ? 'text-match-hit' : 'text-match-miss']"
                      >
                        <span>{{ match.matched ? '文本命中' : '文本未命中' }}</span>
                        <span :title="match.textSnippet">{{ previewChunk(match.textSnippet) }}</span>
                      </div>
                    </div>
                  </td>
                  <td class="chunk-cell">
                    <div v-if="(r.recalledChunks || []).length" class="chunk-preview-list">
                      <div
                        v-for="chunk in (r.recalledChunks || []).slice(0, 3)"
                        :key="chunk.chunkId"
                        class="chunk-preview-item"
                        @click="openChunkDetail(chunk, '实际召回 Chunk')"
                      >
                        <div class="chunk-preview-head">
                          <span class="chunk-preview-id">#{{ chunk.chunkId }}</span>
                          <span class="chunk-preview-meta">chunk {{ chunk.chunkIndex ?? '-' }}</span>
                        </div>
                        <div class="chunk-preview-text" :title="chunk.content">{{ previewChunk(chunk.content) }}</div>
                      </div>
                      <div v-if="(r.recalledChunks || []).length > 3" class="chunk-preview-more">+{{ (r.recalledChunks || []).length - 3 }} 条</div>
                    </div>
                    <span v-else :title="formatChunkIds(r.recalledChunkIds)">{{ formatChunkIds(r.recalledChunkIds) }}</span>
                  </td>
                  <td>
                    <span v-if="r.hitAt != null && r.hitAt >= 0" class="hit-badge hit-ok">#{{ r.hitAt }}</span>
                    <span v-else class="hit-badge hit-miss">未命中</span>
                  </td>
                  <td><span :class="['hit-mark', r.top1Hit ? 'hit-yes' : 'hit-no']">{{ r.top1Hit ? '✓' : '✗' }}</span></td>
                  <td><span :class="['hit-mark', r.top3Hit ? 'hit-yes' : 'hit-no']">{{ r.top3Hit ? '✓' : '✗' }}</span></td>
                  <td class="metric-cell">{{ (r.mrr ?? 0).toFixed(4) }}</td>
                  <td>{{ r.latencyMs ?? 0 }}ms</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="state-hint" style="padding:24px 0">
            <div class="state-desc">暂无逐题详细结果</div>
          </div>

          <!-- 策略对比 -->
          <div v-if="compareExperiments.length > 1" style="margin-top:24px;">
            <div class="section-title">
              同数据集策略对比（Top3 命中率）
              <span style="font-weight:400;font-size:11px;color:var(--text-muted);margin-left:8px;">
                柱子越高 = 该策略在此数据集上检索越准
              </span>
            </div>
            <div class="bar-chart-wrap">
              <div class="bar-chart">
                <div v-for="exp in compareExperiments" :key="exp.id" class="bar-col">
                  <div class="bar-val">{{ formatRate(exp.top3HitRate) }}</div>
                  <div
                    class="bar"
                    :class="{ 'bar-best': Number(exp.top3HitRate || 0) === bestCompareRate }"
                    :style="{ height: `${Math.max(12, Number(exp.top3HitRate || 0) * 100)}px` }"
                  ></div>
                  <div class="bar-label">{{ strategyLabelMap[exp.strategy] || exp.strategy }}</div>
                </div>
              </div>
              <!-- 80% 参考线 -->
              <div class="bar-ref-line" style="bottom:80px;">
                <span class="bar-ref-label">80%</span>
              </div>
            </div>
          </div>

          <!-- 失败样本 -->
          <div v-if="(experimentDetail.failureSamples || []).length" class="section-title" style="margin-top:20px;">
            失败样本分析
          </div>
          <table v-if="(experimentDetail.failureSamples || []).length" class="data-table" style="margin-top:8px;">
            <thead><tr><th>问题</th><th>失败原因</th><th>命中情况</th><th>期望 Chunk</th><th>实际召回 Chunk</th><th>优化建议</th></tr></thead>
            <tbody>
              <tr v-for="item in experimentDetail.failureSamples || []" :key="item.questionId">
                <td>{{ item.question }}</td>
                <td>
                  <span class="badge" :class="failureBadgeClass(item.failureReason)">{{ item.failureReason }}</span>
                </td>
                <td>{{ failureRankText(item) }}</td>
                <td class="chunk-cell">
                  <div v-if="(item.expectedChunks || []).length" class="chunk-preview-list">
                    <div
                      v-for="chunk in (item.expectedChunks || []).slice(0, 2)"
                      :key="chunk.chunkId"
                      class="chunk-preview-item"
                      @click="openChunkDetail(chunk, '期望 Chunk')"
                    >
                      <div class="chunk-preview-head">
                        <span class="chunk-preview-id">#{{ chunk.chunkId }}</span>
                        <span class="chunk-preview-meta">chunk {{ chunk.chunkIndex ?? '-' }}</span>
                      </div>
                      <div class="chunk-preview-text" :title="chunk.content">{{ previewChunk(chunk.content) }}</div>
                    </div>
                    <div v-if="(item.expectedChunks || []).length > 2" class="chunk-preview-more">+{{ (item.expectedChunks || []).length - 2 }} 条</div>
                  </div>
                  <span v-else :title="formatChunkIds(item.expectedChunkIds)">{{ formatChunkIds(item.expectedChunkIds) }}</span>
                  <div v-if="(item.expectedTextSnippets || []).length" class="text-match-list">
                    <div
                      v-for="match in item.expectedTextMatches || []"
                      :key="match.textSnippet"
                      :class="['text-match-item', match.matched ? 'text-match-hit' : 'text-match-miss']"
                    >
                      <span>{{ match.matched ? '文本命中' : '文本未命中' }}</span>
                      <span :title="match.textSnippet">{{ previewChunk(match.textSnippet) }}</span>
                    </div>
                  </div>
                </td>
                <td class="chunk-cell">
                  <div v-if="(item.recalledChunks || []).length" class="chunk-preview-list">
                    <div
                      v-for="chunk in (item.recalledChunks || []).slice(0, 2)"
                      :key="chunk.chunkId"
                      class="chunk-preview-item"
                      @click="openChunkDetail(chunk, '实际召回 Chunk')"
                    >
                      <div class="chunk-preview-head">
                        <span class="chunk-preview-id">#{{ chunk.chunkId }}</span>
                        <span class="chunk-preview-meta">chunk {{ chunk.chunkIndex ?? '-' }}</span>
                      </div>
                      <div class="chunk-preview-text" :title="chunk.content">{{ previewChunk(chunk.content) }}</div>
                    </div>
                    <div v-if="(item.recalledChunks || []).length > 2" class="chunk-preview-more">+{{ (item.recalledChunks || []).length - 2 }} 条</div>
                  </div>
                  <span v-else :title="formatChunkIds(item.recalledChunkIds)">{{ formatChunkIds(item.recalledChunkIds) }}</span>
                </td>
                <td>{{ item.suggestion || failureSuggestion(item.failureReason, experimentDetail.strategy) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="showQuickStart" class="modal-mask" @click.self="showQuickStart = false">
        <div class="modal modal-wide">
          <h3 class="modal-title">⚡ 快速体验 — 自动弱标注</h3>
          <div class="field">
            <span>选择知识库（必须已上传并完成解析文档）*</span>
            <select v-model="quickStartKbId" class="select" @change="preloadQuickStartChunks">
              <option :value="null" disabled>请选择知识库</option>
              <option v-for="kb in kbList" :key="kb.id" :value="kb.id">
                {{ kb.name }}
              </option>
            </select>
          </div>
          <div class="field">
            <span>数据集名称</span>
            <input v-model="quickStartName" type="text" />
          </div>
          <div class="quick-questions-label">
            预置题目与自动标注 Chunk（{{ currentQuickStartQuestions.length }} 道）：
          </div>
          <div class="quick-questions-list">
            <div v-for="(item, i) in quickStartItems" :key="item.question" class="quick-q-item">
              <span class="quick-q-num">{{ i + 1 }}</span>
              <div class="quick-q-body">
                <div class="quick-q-title">{{ item.question }}</div>
                <div v-if="quickStartLoading" class="quick-chunk-muted">正在用混合检索选择期望 Chunk…</div>
                <div v-else-if="item.chunk" class="quick-chunk-card" @click="openChunkDetail(item.chunk, '自动标注 Chunk')">
                  <div class="quick-chunk-head">
                    <span>{{ item.chunk.filename || '未知文档' }} #{{ item.chunk.chunkIndex ?? '-' }}</span>
                    <span class="quick-chunk-score">{{ Number(item.chunk.score || 0).toFixed(4) }}</span>
                  </div>
                  <div class="quick-chunk-text" :title="item.chunk.content">{{ candidatePreview(item.chunk.content) }}</div>
                </div>
                <div v-else class="quick-chunk-muted">未检索到可标注 Chunk</div>
              </div>
            </div>
          </div>
          <div class="field" style="margin-top:12px;">
            <span style="color:var(--text-muted);font-size:11px;">
              {{ quickStartHint }}
            </span>
          </div>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showQuickStart = false">取消</button>
            <button class="btn-accent" :disabled="creatingQuickStart || quickStartLoading || !quickStartReady" @click="onCreateQuickStart">
              {{ creatingQuickStart ? '创建中…' : '一键创建数据集和题目' }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="showChunkDetail && selectedChunkDetail" class="modal-mask" @click.self="showChunkDetail = false">
        <div class="modal chunk-detail-modal">
          <div class="chunk-detail-head">
            <div>
              <h3 class="modal-title">{{ selectedChunkDetail.title }}</h3>
              <div class="chunk-detail-meta">
                #{{ selectedChunkDetail.chunk.chunkId }}
                <span>chunk {{ selectedChunkDetail.chunk.chunkIndex ?? '-' }}</span>
                <span v-if="selectedChunkDetail.chunk.tokenCount != null">{{ selectedChunkDetail.chunk.tokenCount }} tokens</span>
              </div>
            </div>
            <button class="chunk-detail-close" @click="showChunkDetail = false">关闭</button>
          </div>
          <pre class="chunk-detail-content">{{ selectedChunkDetail.chunk.content || '—' }}</pre>
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
            <button class="btn btn-secondary" @click="showBatchImport = false">取消</button>
            <button class="btn btn-primary" :disabled="submittingBatch" @click="onBatchImport">
              导入
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  batchCreateEvalQuestions,
  createEvalDataset,
  createEvalQuestion,
  deleteExperiment,
  deleteEvalDataset,
  deleteEvalQuestion,
  getExperiment,
  listEvalDatasets,
  listExperiments,
  listEvalQuestions,
  runChunkerAb,
  runExperiment,
  updateEvalQuestion,
} from '../api/eval'
import { listKb } from '../api/kb'
import { search as searchApi } from '../api/search'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { useToast } from '../composables/useToast'
import Pager from '../components/Pager.vue'

const toast = useToast()
const route = useRoute()
const router = useRouter()

function goToKnowledge() {
  router.push('/knowledge')
}

const activeTab = ref('datasets')
const datasets = ref([])
const kbList = ref([])
const loadingDatasets = ref(false)
const expandedDatasetId = ref(null)
const showAdvanced = ref(false)

const strategyLabelMap = {
  vector: '向量检索',
  keyword: '关键词检索',
  hybrid: '混合检索',
  full: '全链路',
  rewrite: 'Query改写',
}

const chunkerStrategyOptions = [
  { value: 'RECURSIVE', label: 'Recursive', hint: '递归切分' },
  { value: 'MARKDOWN_HEADING', label: 'Markdown Heading', hint: '按标题分块' },
  { value: 'STRUCTURED_HEADING', label: 'Structured Heading', hint: '中文结构标记分块' },
  { value: 'SEMANTIC', label: 'Semantic', hint: '语义分块' },
  { value: 'TABLE_AWARE', label: 'Table Aware', hint: '表格感知' },
  { value: 'FIXED_WINDOW', label: 'Fixed Window', hint: '固定窗口' },
]

const judgeTagOptions = [
  { value: 'core', label: 'core（核心）' },
  { value: 'regression', label: 'regression（回归）' },
  { value: 'business', label: 'business（业务）' },
  { value: 'retrieval', label: 'retrieval（检索）' },
  { value: 'answer', label: 'answer（答案）' },
  { value: 'pii', label: 'pii（脱敏）' },
]

const questionsMap = reactive({})
const questionsLoading = reactive({})
const questionPage = reactive({})

const showCreateDataset = ref(false)
const submittingDataset = ref(false)
const datasetForm = ref({ name: '', kbId: null })

const showAddQuestion = ref(false)
const submittingQuestion = ref(false)
const searchingCandidates = ref(false)
const addQuestionDatasetId = ref(null)
const editingQuestionId = ref(null)
const addQuestionStep = ref(1)
const questionForm = ref({ question: '' })
const expectedTextSnippetsInput = ref('')
const candidateChunks = ref([])
const selectedChunkIds = ref([])

const showQuickStart = ref(false)
const creatingQuickStart = ref(false)
const quickStartLoading = ref(false)
const quickStartKbId = ref(null)
const quickStartName = ref('')
const quickStartItems = ref([])

const quickStartQuestions = [
  '这份知识库主要讲了哪些内容？',
  '文档中有哪些关键能力、经验或结论？',
  '请找出和项目实践、技术方案或业务结果相关的内容。',
]

const currentQuickStartQuestions = computed(() => quickStartQuestions)
const quickStartReady = computed(() =>
  quickStartItems.value.length === currentQuickStartQuestions.value.length
  && quickStartItems.value.every((item) => item.chunk?.chunkId != null)
)
const quickStartHint = computed(() => {
  if (creatingQuickStart.value) return '正在创建数据集并保存自动弱标注题目…'
  if (quickStartLoading.value) return '正在用混合检索从所选知识库预选期望 Chunk…'
  if (!quickStartKbId.value) return '请先选择一个已上传并完成解析文档的知识库。'
  if (!quickStartReady.value) return '当前知识库没有检索到足够的可标注 Chunk，请先上传并解析文档。'
  return '已完成自动弱标注，创建时会直接保存当前展示的 Chunk，不会再次检索；建议后续人工复核。'
})

const showBatchImport = ref(false)
const submittingBatch = ref(false)
const batchDatasetId = ref(null)
const batchText = ref('')

const experiments = ref([])
const loadingExperiments = ref(false)
const expKeyword = ref('')
const expPage = ref(1)
const expTotal = ref(0)
const expSize = ref(10)
let expSearchTimer = null
const showRunExperiment = ref(false)
const runningExperiment = ref(false)
const showExperimentDetail = ref(false)
const experimentDetail = ref(null)
const showChunkDetail = ref(false)
const selectedChunkDetail = ref(null)
const runForm = ref({
  datasetId: null,
  strategy: 'full',
  vectorWeight: 0.55,
  topK: 8,
  ablation: false,
})
const runningChunkerAb = ref(false)
const chunkerAbResults = ref([])
const chunkerAbForm = ref({
  evalDatasetId: null,
  strategies: ['RECURSIVE', 'MARKDOWN_HEADING', 'SEMANTIC'],
  params: {
    chunkSize: 500,
    overlap: 50,
    separators: ['\n\n', '\n', '。', ','],
    simThreshold: 0.65,
    tablePolicy: 'WHOLE',
  },
})

const parsedExpectedTextSnippets = computed(() => parseExpectedTextSnippets(expectedTextSnippetsInput.value))

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const res = await listEvalDatasets()
    datasets.value = res.data ?? []
    if (!chunkerAbForm.value.evalDatasetId && datasets.value.length) {
      chunkerAbForm.value.evalDatasetId = datasets.value[0].id
    }
  } finally {
    loadingDatasets.value = false
  }
}

async function loadExperiments(page = expPage.value) {
  loadingExperiments.value = true
  try {
    const res = await listExperiments({
      page,
      size: expSize.value,
      datasetName: expKeyword.value.trim() || undefined,
    })
    const data = res.data ?? {}
    experiments.value = data.list ?? []
    expTotal.value = data.total ?? 0
    expPage.value = page
  } finally {
    loadingExperiments.value = false
  }
}

function onExpSize(size) {
  expSize.value = size
  loadExperiments(1)
}

function onExpSearchInput() {
  clearTimeout(expSearchTimer)
  expSearchTimer = setTimeout(() => loadExperiments(1), 300)
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
    toast.warning('请填写数据集名称')
    return
  }
  if (!datasetForm.value.kbId) {
    toast.warning('请选择关联知识库')
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
  const ok = await confirmDialog({
    title: '删除数据集',
    message: `确定删除数据集「${ds.name}」？`,
    detail: '关联题目将一并删除。',
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteEvalDataset(ds.id)
    if (expandedDatasetId.value === ds.id) expandedDatasetId.value = null
    delete questionsMap[ds.id]
    await loadDatasets()
    toast.success('数据集已删除')
  } catch {
    // 全局拦截器已 toast
  }
}

function openAddQuestion(datasetId) {
  addQuestionDatasetId.value = datasetId
  editingQuestionId.value = null
  addQuestionStep.value = 1
  questionForm.value = { question: '', judgeEnabled: false, judgeTags: [] }
  expectedTextSnippetsInput.value = ''
  candidateChunks.value = []
  selectedChunkIds.value = []
  showAddQuestion.value = true
}

function openEditQuestion(datasetId, question) {
  addQuestionDatasetId.value = datasetId
  editingQuestionId.value = question.id
  addQuestionStep.value = 1
  questionForm.value = {
    question: question.question || '',
    judgeEnabled: Boolean(question.judgeEnabled),
    judgeTags: [...(question.judgeTags || [])],
  }
  expectedTextSnippetsInput.value = (question.expectedTextSnippets || []).join('\n')
  candidateChunks.value = []
  selectedChunkIds.value = [...(question.expectedChunkIds || [])]
  showAddQuestion.value = true
}

async function onSearchCandidates() {
  if (!questionForm.value.question?.trim()) {
    toast.warning('请先填写问题')
    return
  }
  searchingCandidates.value = true
  try {
    // 限定在数据集关联的知识库内检索
    const ds = datasets.value.find((d) => d.id === addQuestionDatasetId.value)
    const res = await searchApi({
      query: questionForm.value.question.trim(),
      strategy: 'hybrid',
      topK: 8,
      rerankTopN: 5,
      kbIds: ds?.kbId != null ? [ds.kbId] : undefined,
    })
    candidateChunks.value = (res.data?.results ?? []).map((item) => ({
      chunkId: item.chunkId,
      filename: item.filename || '未知文档',
      chunkIndex: item.chunkIndex ?? '-',
      content: item.content || '',
      score: item.finalScore ?? item.vectorScore ?? item.bm25Score ?? 0,
    }))
    addQuestionStep.value = 2
  } catch {
    toast.error('检索候选 Chunk 失败，请稍后重试')
  } finally {
    searchingCandidates.value = false
  }
}

function candidatePreview(content) {
  const text = (content || '').replace(/\s+/g, ' ').trim()
  if (text.length <= 200) return text
  return `${text.slice(0, 200)}…`
}

async function onAddQuestion() {
  if (!questionForm.value.question?.trim()) {
    toast.warning('请填写问题')
    return
  }
  submittingQuestion.value = true
  try {
    const payload = {
      question: questionForm.value.question.trim(),
      expectedChunkIds: selectedChunkIds.value,
      expectedTextSnippets: parsedExpectedTextSnippets.value,
      judgeEnabled: Boolean(questionForm.value.judgeEnabled),
      judgeTags: questionForm.value.judgeTags || [],
    }
    if (editingQuestionId.value) {
      await updateEvalQuestion(addQuestionDatasetId.value, editingQuestionId.value, payload)
    } else {
      await createEvalQuestion(addQuestionDatasetId.value, payload)
    }
    showAddQuestion.value = false
    await loadDatasets()
    await loadQuestions(addQuestionDatasetId.value, questionPage[addQuestionDatasetId.value] || 1)
  } finally {
    submittingQuestion.value = false
  }
}

async function onToggleJudgeEnabled(datasetId, question, enabled) {
  await updateQuestionJudgeFields(datasetId, question, {
    judgeEnabled: enabled,
    judgeTags: question.judgeTags || [],
  })
}

async function onJudgeTagsChange(datasetId, question, event) {
  const tags = Array.from(event.target.selectedOptions || []).map((option) => option.value)
  await updateQuestionJudgeFields(datasetId, question, {
    judgeEnabled: Boolean(question.judgeEnabled),
    judgeTags: tags,
  })
}

async function updateQuestionJudgeFields(datasetId, question, judgeFields) {
  const previous = {
    judgeEnabled: Boolean(question.judgeEnabled),
    judgeTags: [...(question.judgeTags || [])],
  }
  question.judgeEnabled = Boolean(judgeFields.judgeEnabled)
  question.judgeTags = [...(judgeFields.judgeTags || [])]
  try {
    await updateEvalQuestion(datasetId, question.id, {
      question: question.question,
      expectedChunkIds: question.expectedChunkIds || [],
      expectedTextSnippets: question.expectedTextSnippets || [],
      judgeEnabled: question.judgeEnabled,
      judgeTags: question.judgeTags,
    })
  } catch {
    question.judgeEnabled = previous.judgeEnabled
    question.judgeTags = previous.judgeTags
    // 全局拦截器已 toast
  }
}

function openBatchImport(datasetId) {
  batchDatasetId.value = datasetId
  batchText.value = ''
  showBatchImport.value = true
}

async function openQuickStart() {
  quickStartKbId.value = kbList.value.length ? kbList.value[0].id : null
  quickStartName.value = '快速体验评测集'
  quickStartItems.value = currentQuickStartQuestions.value.map((question) => ({ question, chunk: null }))
  showQuickStart.value = true
  await preloadQuickStartChunks()
}

async function preloadQuickStartChunks() {
  quickStartItems.value = currentQuickStartQuestions.value.map((question) => ({ question, chunk: null }))
  if (!quickStartKbId.value) return
  quickStartLoading.value = true
  try {
    const loadedItems = []
    for (const question of currentQuickStartQuestions.value) {
      try {
        const res = await searchApi({
          query: question,
          strategy: 'hybrid',
          topK: 3,
          rerankTopN: 3,
          kbIds: [quickStartKbId.value],
        })
        const first = (res.data?.results ?? []).find((item) => item.chunkId != null)
        loadedItems.push({
          question,
          chunk: first
            ? {
                chunkId: first.chunkId,
                filename: first.filename || '未知文档',
                chunkIndex: first.chunkIndex ?? '-',
                content: first.content || '',
                score: first.finalScore ?? first.vectorScore ?? first.bm25Score ?? 0,
              }
            : null,
        })
      } catch {
        loadedItems.push({ question, chunk: null })
      }
    }
    quickStartItems.value = loadedItems
  } finally {
    quickStartLoading.value = false
  }
}

async function onCreateQuickStart() {
  if (!quickStartKbId.value) {
    toast.warning('请选择知识库')
    return
  }
  if (!quickStartName.value?.trim()) {
    toast.warning('请填写数据集名称')
    return
  }
  if (!quickStartReady.value) {
    toast.warning('当前知识库没有检索到足够的可标注 Chunk，暂不能创建快速体验数据集')
    return
  }
  creatingQuickStart.value = true
  try {
    // 1. 创建数据集
    const ds = await createEvalDataset({
      name: quickStartName.value.trim(),
      kbId: quickStartKbId.value,
    })
    const datasetId = ds.data?.id
    if (!datasetId) {
      toast.error('数据集创建失败')
      return
    }

    // 2. 直接复用弹窗中已经预检索出的自动弱标注 Chunk，不再重复检索。
    const questionsWithChunks = quickStartItems.value.map((item) => ({
      question: item.question,
      expectedChunkIds: [item.chunk.chunkId],
    }))

    // 3. 批量导入
    await batchCreateEvalQuestions(datasetId, questionsWithChunks)
    showQuickStart.value = false
    await loadDatasets()
    if (datasetId) {
      expandedDatasetId.value = datasetId
      await loadQuestions(datasetId, 1)
    }
  } catch {
    // 全局拦截器已 toast
  } finally {
    creatingQuickStart.value = false
  }
}

async function onBatchImport() {
  const lines = batchText.value
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  if (!lines.length) {
    toast.warning('请至少输入一行问题')
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
  const ok = await confirmDialog({
    title: '删除题目',
    message: `确定删除题目「${question.question.slice(0, 30)}…」？`,
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteEvalQuestion(datasetId, question.id)
    await loadDatasets()
    await loadQuestions(datasetId, questionPage[datasetId] || 1)
    toast.success('题目已删除')
  } catch {
    // 全局拦截器已 toast
  }
}

function openRunExperiment(datasetId) {
  runForm.value = {
    datasetId: datasetId || (datasets.value.length ? datasets.value[0].id : null),
    strategy: 'full',
    vectorWeight: 0.55,
    topK: 8,
    ablation: false,
  }
  showRunExperiment.value = true
}

async function onRunExperiment() {
  if (!runForm.value.datasetId) {
    toast.warning('请选择数据集')
    return
  }
  runningExperiment.value = true
  try {
    if (runForm.value.ablation) {
      const strategies = ['vector', 'keyword', 'hybrid', 'full', 'rewrite']
      for (const strategy of strategies) {
        await runExperiment({
          datasetId: runForm.value.datasetId,
          strategy,
          vectorWeight: runForm.value.vectorWeight,
          topK: runForm.value.topK,
        })
      }
    } else {
      await runExperiment({
        datasetId: runForm.value.datasetId,
        strategy: runForm.value.strategy,
        vectorWeight: runForm.value.vectorWeight,
        topK: runForm.value.topK,
      })
    }
    showRunExperiment.value = false
    activeTab.value = 'experiments'
    await loadExperiments()
  } finally {
    runningExperiment.value = false
  }
}

async function onRunChunkerAb() {
  if (!chunkerAbForm.value.evalDatasetId) {
    toast.warning('请选择评测数据集')
    return
  }
  if (!chunkerAbForm.value.strategies.length) {
    toast.warning('请至少选择一种分块策略')
    return
  }
  runningChunkerAb.value = true
  try {
    const res = await runChunkerAb(chunkerAbForm.value)
    chunkerAbResults.value = res.data?.results ?? []
  } catch {
    // 全局拦截器已 toast
  } finally {
    runningChunkerAb.value = false
  }
}

function chunkerStrategyLabel(strategy) {
  const item = chunkerStrategyOptions.find((entry) => entry.value === strategy)
  if (!item) return strategy
  return item.hint ? `${item.label}（${item.hint}）` : item.label
}

async function openExperimentDetail(id) {
  const res = await getExperiment(id)
  experimentDetail.value = res.data ?? null
  showExperimentDetail.value = true
}

async function onDeleteExperiment(exp) {
  const ok = await confirmDialog({
    title: '删除实验',
    message: `确定删除实验 #${exp.id}？`,
    confirmText: '删除',
    cancelText: '取消',
    variant: 'danger',
  })
  if (!ok) return
  try {
    await deleteExperiment(exp.id)
    await loadExperiments()
    toast.success('实验已删除')
  } catch {
    // 全局拦截器已 toast
  }
}

function formatTime(iso) {
  if (!iso) return '-'
  return iso.toString().replace('T', ' ').slice(0, 19)
}

function formatChunkIds(ids) {
  if (!ids?.length) return '—'
  return ids.join(', ')
}

function parseExpectedTextSnippets(raw) {
  return (raw || '')
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean)
}

function formatTextSnippets(snippets) {
  if (!snippets?.length) return '—'
  return snippets.map((item) => previewChunk(item)).join(' / ')
}

function kbNameOf(ds) {
  if (!ds) return '—'
  const kb = kbList.value.find((item) => item.id === ds.kbId)
  return kb?.name || (ds.kbId != null ? `KB #${ds.kbId}` : '未关联知识库')
}

function previewChunk(content) {
  const text = (content || '').replace(/\s+/g, ' ').trim()
  if (!text) return '—'
  return text.length <= 120 ? text : `${text.slice(0, 120)}…`
}

function openChunkDetail(chunk, title) {
  selectedChunkDetail.value = { chunk, title }
  showChunkDetail.value = true
}

function formatRate(v) {
  const n = Number(v ?? 0)
  return `${(n * 100).toFixed(1)}%`
}

function formatMrr(v) {
  const n = Number(v ?? 0)
  return n.toFixed(4)
}

function failureBadgeClass(reason) {
  if (reason === '召回不足') return 'badge-amber'
  if (reason === '无召回结果') return 'badge-red'
  if (reason === '排序不足') return 'badge-red'
  if (reason === '排序错误') return 'badge-red'
  if (reason === '标注缺失') return 'badge-gray'
  return 'badge-blue'
}

function failureRankText(item) {
  if (!item) return '—'
  const recalledCount = item.recalledCount ?? item.recalledChunkIds?.length ?? 0
  const hasChunkIds = !!item.expectedChunkIds?.length
  const hasTextSnippets = !!item.expectedTextSnippets?.length
  if (!hasChunkIds && !hasTextSnippets) return `未标注标准 Chunk，召回 ${recalledCount} 条`
  if (!item.recalledChunkIds?.length) return '没有召回结果'
  if (item.expectedBestRank != null) {
    return `标准标注在第 ${item.expectedBestRank} 位，未进入 Top3`
  }
  return `标准标注未进入 TopK，召回 ${recalledCount} 条`
}

function failureSuggestion(reason, strategy) {
  if (reason === '无召回结果') {
    return '没有返回任何 Chunk。确认文档已处理完成、知识库范围正确后，提高 TopK 或切换 hybrid/full 重试'
  }
  if (reason === '召回不足') {
    if (strategy === 'vector') return '提高 TopK、补充知识库中相关文档'
    if (strategy === 'keyword') return 'BM25 可能遗漏了重要术语，尝试 hybrid 或补充文档关键词'
    if (strategy === 'rewrite') return '改写后的查询可能偏离原意，检查 Query改写 质量或提高 TopK'
    return '提高 TopK（如 8→15）、补充知识库相关文档'
  }
  if (reason === '排序不足') {
    if (strategy === 'full') return '标准 Chunk 已进 TopK 但未进 Top3，检查标注内容或扩大 Rerank TopN'
    return '标准 Chunk 已进 TopK 但排序靠后，尝试 hybrid/full 或调整权重'
  }
  if (reason === '排序错误') {
    if (strategy === 'vector') return '向量语义匹配不够精准，尝试 hybrid 或 full 引入 Reranker'
    if (strategy === 'keyword') return 'BM25 排序依赖词频，无法理解语义，升级到 hybrid 或 full'
    if (strategy === 'full') return '全链路已启用 Reranker 但仍然排错，可能是 Reranker 不理解该领域术语，检查知识库文档质量'
    if (strategy === 'rewrite') return '改写查询召回了正确 chunk 但排在后面，可叠加 hybrid 提升排序质量'
    return '尝试切换到 hybrid 或 full 策略'
  }
  if (reason === '标注缺失') return '未检索到足够结果，检查文档是否已处理完成、分块是否正常，或提高 TopK'
  return '补充相关文档或修正标注'
}

const bestCompareRate = computed(() => {
  if (!experiments.value.length) return 0
  return Math.max(...experiments.value.map(e => Number(e.top3HitRate ?? 0)))
})

const compareExperiments = computed(() => {
  const dsId = experimentDetail.value?.datasetId
  if (!dsId) return []
  // 同策略取最新一条，避免重复柱子
  const seen = new Map()
  for (const item of experiments.value) {
    if (item.datasetId === dsId && !seen.has(item.strategy)) {
      seen.set(item.strategy, item)
    }
  }
  return [...seen.values()]
})

const totalQuestions = computed(() =>
  datasets.value.reduce((sum, ds) => sum + (ds.questionCount ?? 0), 0)
)

const totalExperiments = computed(() => experiments.value.length)

const bestTop3Rate = computed(() => {
  if (!experiments.value.length) return '—'
  const best = Math.max(...experiments.value.map(e => Number(e.top3HitRate ?? 0)))
  return `${(best * 100).toFixed(1)}%`
})

const avgMrr = computed(() => {
  if (!experiments.value.length) return '—'
  const sum = experiments.value.reduce((s, e) => s + Number(e.mrr ?? 0), 0)
  return (sum / experiments.value.length).toFixed(4)
})

const avgLatency = computed(() => {
  if (!experiments.value.length) return '—'
  const sum = experiments.value.reduce((s, e) => s + Number(e.avgLatencyMs ?? 0), 0)
  return Math.round(sum / experiments.value.length)
})

onMounted(async () => {
  try {
    const res = await listKb()
    kbList.value = res.data ?? []
  } catch {
    kbList.value = []
  }
  await loadDatasets()
  await loadExperiments()

  // 从调试台保存用例后跳转过来，自动展开目标数据集
  const datasetId = route.query.datasetId
  const tab = route.query.tab
  if (tab) activeTab.value = tab
  if (datasetId) {
    const id = Number(datasetId)
    if (!Number.isNaN(id) && datasets.value.some(ds => ds.id === id)) {
      expandedDatasetId.value = id
      await loadQuestions(id, 1)
    }
  }
})
</script>

<style scoped>
.page-body { padding: 20px 28px 32px; }

/* ====== Onboarding ====== */
.eval-onboard {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 32px 28px;
  text-align: center;
}
.onboard-hero { margin-bottom: 24px; }
.onboard-icon { font-size: 40px; margin-bottom: 8px; }
.onboard-title { font-size: 20px; font-weight: 700; color: var(--slate); margin-bottom: 8px; }
.onboard-desc { font-size: 13px; color: var(--text-muted); line-height: 1.8; }
.onboard-steps {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
  text-align: left;
}
.onboard-step {
  flex: 1;
  display: flex;
  gap: 12px;
  padding: 14px;
  background: #f8fafc;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
}
.step-num {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.step-body { min-width: 0; }
.step-title { font-size: 13px; font-weight: 600; color: var(--slate); margin-bottom: 4px; }
.step-desc { font-size: 11px; color: var(--text-muted); line-height: 1.5; }
.onboard-actions { display: flex; justify-content: center; gap: 12px; }

/* ====== Accent Button ====== */
.btn-accent {
  background: #fff;
  color: var(--purple);
  border: 1px solid var(--purple);
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-accent:hover { background: #f5f3ff; }
.btn-accent:disabled { opacity: 0.6; cursor: not-allowed; }

/* ====== Quick Start ====== */
.quick-questions-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--slate);
  margin-bottom: 8px;
}
.quick-questions-list {
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  max-height: 220px;
  overflow-y: auto;
}
.quick-q-item {
  padding: 5px 0;
  font-size: 12px;
  color: var(--gray);
  display: flex;
  gap: 8px;
  align-items: flex-start;
  border-bottom: 1px solid rgba(0,0,0,0.04);
}
.quick-q-item:last-child { border-bottom: none; }
.quick-q-num {
  color: var(--text-muted);
  font-weight: 600;
  flex-shrink: 0;
  font-size: 11px;
}
.quick-q-body {
  flex: 1;
  min-width: 0;
}
.quick-q-title {
  color: var(--slate);
  font-weight: 600;
  line-height: 1.5;
}
.quick-chunk-card {
  margin-top: 6px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  padding: 8px;
  cursor: pointer;
}
.quick-chunk-card:hover {
  border-color: #c4b5fd;
}
.quick-chunk-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--text);
  font-size: 11px;
  margin-bottom: 4px;
}
.quick-chunk-score {
  color: var(--text-muted);
  font-family: 'SF Mono', Monaco, monospace;
  flex-shrink: 0;
}
.quick-chunk-text {
  color: var(--gray);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}
.quick-chunk-muted {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
}

/* ====== Per-Question Results ====== */
.per-question-table {
  max-height: 40vh;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.per-question-table .data-table { font-size: 12px; }
.per-question-table .data-table thead th { font-size: 10px; padding: 8px 10px; position: sticky; top: 0; z-index: 1; }
.per-question-table .data-table tbody td { padding: 8px 10px; }
.question-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
}
.chunk-cell {
  font-size: 10px;
  max-width: 260px;
  vertical-align: top;
}
.text-snippet-line {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.4;
  word-break: break-word;
}
.golden-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text);
}
.core-badge {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 7px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 700;
  color: #a35a00;
  background: #fff6e9;
  border: 1px solid #f0d29a;
  white-space: nowrap;
}
.link-action.is-locked {
  color: #a35a00;
  cursor: not-allowed;
}
.tag-select {
  width: 132px;
  min-height: 58px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 4px;
  font-size: 12px;
  background: #fff;
}
.chunk-preview-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.chunk-preview-item {
  background: #f8fafc;
  border: 1px solid rgba(148, 163, 184, 0.24);
  border-radius: 6px;
  padding: 6px 8px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.chunk-preview-item:hover {
  background: #fff;
  border-color: rgba(37, 99, 235, 0.35);
}
.chunk-preview-head {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 3px;
  font-family: 'SF Mono', Monaco, monospace;
}
.chunk-preview-id {
  color: var(--primary);
  font-weight: 700;
}
.chunk-preview-meta {
  color: var(--text-muted);
  font-size: 9px;
}
.chunk-preview-text {
  color: var(--text);
  line-height: 1.45;
  white-space: normal;
  word-break: break-word;
}
.chunk-preview-more {
  color: var(--text-muted);
  font-size: 9px;
  padding-left: 2px;
}
.text-match-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}
.text-match-item {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 6px;
  align-items: start;
  border-radius: 6px;
  padding: 5px 7px;
  line-height: 1.4;
  word-break: break-word;
}
.text-match-hit {
  background: rgba(16, 185, 129, 0.08);
  color: #047857;
}
.text-match-miss {
  background: rgba(239, 68, 68, 0.08);
  color: #b91c1c;
}
.ab-panel {
  display: grid;
  gap: 16px;
}
.ab-form {
  display: grid;
  gap: 14px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.strategy-checks {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.check-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #f8fafc;
  font-size: 13px;
  color: var(--slate);
}
.field-hint {
  color: var(--text-muted);
  font-weight: 400;
  font-size: 11px;
}
.ab-param-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.chunk-detail-modal {
  max-width: 780px;
  width: min(780px, calc(100vw - 32px));
}
.chunk-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.chunk-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
  color: var(--text-muted);
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 10px;
}
.chunk-detail-close {
  border: 1px solid var(--border);
  background: #fff;
  border-radius: var(--radius-sm);
  padding: 6px 10px;
  cursor: pointer;
  color: var(--text);
}
.chunk-detail-content {
  max-height: 56vh;
  overflow: auto;
  margin: 0;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #f8fafc;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.75;
}
.hit-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
}
.hit-badge.hit-ok { background: #dcfce7; color: #166534; }
.hit-badge.hit-miss { background: #fee2e2; color: #991b1b; }
.hit-mark { font-weight: 700; font-size: 13px; }
.hit-mark.hit-yes { color: #10b981; }
.hit-mark.hit-no { color: #dc2626; }
.row-fail { background: #fff5f5; }

.tab-bar {
  display: flex;
  gap: 0;
  margin-bottom: 20px;
  border-bottom: 2px solid var(--border);
}

.tab-btn {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  padding: 8px 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  color: var(--text-muted);
  transition: all 0.15s;
}
.tab-btn:hover { color: var(--slate); }
.tab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}

.top-toolbar { margin-bottom: 12px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.toolbar-left { display: flex; gap: 10px; align-items: center; }
.toolbar-right { display: flex; align-items: center; gap: 6px; }
/* 与知识库搜索框保持一致 */
.kb-search { position: relative; }
.kb-search input { height: 36px; width: 240px; padding: 0 32px; border: 1px solid var(--border); border-radius: 10px; font-size: 13px; background: #fff; outline: none; transition: .15s; }
.kb-search input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft, #eff4ff); }
.kb-search-ico { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--text-muted); font-size: 13px; pointer-events: none; }
.kb-search-clear { position: absolute; right: 9px; top: 50%; transform: translateY(-50%); color: var(--text-muted); cursor: pointer; font-size: 12px; }

.btn-outline-sm {
  display: inline-block;
  padding: 4px 10px;
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--primary);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-outline-sm:hover { background: #eff6ff; }

/* 次要按钮（刷新等）：干净的白底浅边，悬停变蓝，替代浏览器默认丑按钮 */
.btn-ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: #fff;
  color: var(--slate);
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-ghost:hover:not(:disabled) {
  border-color: var(--primary-border);
  color: var(--primary);
  background: #f8faff;
}
.btn-ghost:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-ghost.btn-sm { height: 34px; padding: 0 14px; font-size: 12.5px; }

/* Summary Cards */
.summary-cards {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.summary-card {
  flex: 1;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: var(--shadow-sm);
  padding: 14px 18px;
  min-width: 0;
}
.summary-num {
  font-size: 24px;
  font-weight: 700;
  color: var(--slate);
  letter-spacing: -0.5px;
}
.summary-num.accent-green { color: #10b981; }
.summary-label {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

/* Strategy Badge */
.strategy-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
  background: var(--light);
  color: var(--gray);
}
.strategy-badge.strategy-vector { background: #dbeafe; color: #1d4ed8; }
.strategy-badge.strategy-keyword { background: #fef3c7; color: #92400e; }
.strategy-badge.strategy-hybrid { background: #ede9fe; color: #6d28d9; }
.strategy-badge.strategy-full { background: #dcfce7; color: #166534; }
.strategy-badge.strategy-rewrite { background: #fce7f3; color: #9d174d; }

/* Metric cells */
.metric-cell { font-weight: 600; font-variant-numeric: tabular-nums; }
.skip-note { color: #9ca3af; font-size: 12px; font-style: italic; }
.metric-accent { color: #10b981; }

/* Actions cell */
/* 操作列保持为表格单元格（不能用 display:flex，否则脱离 border-collapse 导致行分隔线断开） */
.actions-cell { white-space: nowrap; vertical-align: middle; }
.actions-cell > * { vertical-align: middle; }
.actions-cell > * + * { margin-left: 12px; }
/* 冻结基线：删除置灰不可用（创建实验保留可用，用于回归重跑） */
.action-locked { color: var(--text-muted); cursor: not-allowed; }

/* Danger subtle */
.link-action.danger-subtle { color: var(--text-muted); font-size: 11px; }
.link-action.danger-subtle:hover { color: var(--red); }

/* Ablation Toggle */
.ablation-toggle {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 12px;
  transition: border-color 0.2s;
}
.ablation-toggle:hover { border-color: var(--primary); }
.toggle-track {
  width: 40px;
  height: 22px;
  border-radius: 11px;
  background: #cbd5e1;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}
.toggle-track.on { background: var(--primary); }
.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0,0,0,0.15);
  transition: transform 0.2s;
}
.toggle-track.on .toggle-thumb { transform: translateX(18px); }
.toggle-label { font-size: 13px; font-weight: 600; color: var(--slate); }
.toggle-desc { font-size: 11px; color: var(--text-muted); margin-top: 2px; }

/* Advanced Toggle */
.advanced-toggle {
  font-size: 12px;
  color: var(--text-muted);
  cursor: pointer;
  padding: 8px 0;
  user-select: none;
}
.advanced-toggle:hover { color: var(--primary); }


.table-card {
  border: 1px solid var(--border);
  border-radius: 16px;
  overflow: hidden;
  background: #fff;
  box-shadow: var(--shadow-sm);
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
.dataset-title-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.dataset-kb-line {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.3;
}
.dataset-kb-label {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: #e0f2fe;
  color: #0369a1;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
}
.expander { font-size: 16px; color: var(--text-muted); }

.link-action { cursor: pointer; font-size: 12px; color: var(--primary); margin-right: 10px; }
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
.questions-title { font-size: 13px; color: var(--slate); font-weight: 700; }
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

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}
.pager-info { font-size: 12px; color: var(--text-muted); }

.eval-metrics { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; margin-bottom: 24px; }
.eval-card { background: var(--navy); border-radius: 10px; padding: 16px; color: #fff; }
.card-label { font-size: 10px; opacity: 0.5; text-transform: uppercase; letter-spacing: 1px; }
.card-value { font-size: 28px; font-weight: 700; letter-spacing: -1px; }
.section-title { font-weight: 600; font-size: 13px; margin-bottom: 12px; color: var(--slate); }

.bar-chart-wrap {
  position: relative;
  margin-bottom: 24px;
  padding: 0 8px;
}
.bar-chart {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 28px;
  height: 150px;
}
.bar-col { text-align: center; display: flex; flex-direction: column; align-items: center; }
.bar-val {
  font-size: 12px;
  font-weight: 700;
  color: var(--slate);
  margin-bottom: 6px;
  font-variant-numeric: tabular-nums;
}
.bar {
  width: 48px;
  background: #93c5fd;
  border-radius: 4px 4px 0 0;
  transition: height 0.4s ease;
  min-height: 12px;
}
.bar.bar-best { background: var(--primary); }
.bar-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--slate);
  margin-top: 8px;
  white-space: nowrap;
}
.bar-pct { font-size: 10px; color: var(--primary); font-weight: 600; }

.bar-ref-line {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed #d4d4d8;
}
.bar-ref-label {
  position: absolute;
  right: 0;
  top: -10px;
  font-size: 10px;
  color: #a1a1aa;
}
.badge { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.badge-amber { background: #fef3c7; color: #92400e; }
.badge-red { background: #fee2e2; color: #991b1b; }
.badge-gray { background: #f1f5f9; color: #475569; }
.badge-blue { background: #dbeafe; color: #1d4ed8; }

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
.modal-question {
  width: min(760px, 92vw);
  max-height: 80vh;
  overflow: auto;
}
.modal-wide { width: min(520px, 92vw); }
.modal-detail {
  width: min(980px, 95vw);
  max-height: 84vh;
  overflow: auto;
}
.modal-title { font-size: 16px; margin-bottom: 16px; }
.field { display: block; margin-bottom: 14px; }
.checkbox-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
}
.checkbox-row input[type="checkbox"] {
  width: auto;
  margin-top: 2px;
  flex-shrink: 0;
}
.checkbox-row span {
  margin: 0;
  font-size: 13px;
}
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
.question-preview {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--text);
  line-height: 1.6;
}
.selected-count {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 8px;
}
.candidate-list {
  border: 1px solid var(--border);
  border-radius: 8px;
  max-height: 44vh;
  overflow: auto;
}
.candidate-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border-bottom: 1px solid var(--border);
}
.candidate-item:last-child { border-bottom: none; }
.candidate-body { flex: 1; min-width: 0; }
.candidate-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--slate);
  margin-bottom: 4px;
}
.candidate-score {
  font-family: 'SF Mono', Monaco, monospace;
  color: var(--text-muted);
}
.candidate-content {
  color: var(--gray);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .page-body {
    padding: 14px 12px 24px;
  }

  .summary-cards {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .tab-bar {
    position: static;
    margin: 0 0 12px;
    padding: 4px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: #fff;
  }

  .tab-btn {
    flex: 1;
    min-height: 40px;
    padding: 8px 12px;
    font-size: 13px;
    white-space: nowrap;
  }

  .top-toolbar,
  .toolbar-left {
    width: 100%;
  }

  .top-toolbar {
    margin-bottom: 14px;
  }

  .toolbar-left {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  .toolbar-left .btn-primary {
    flex: 1 1 100%;
  }

  .toolbar-left .btn-ghost {
    margin-left: auto;
  }

  .btn-primary,
  .btn-ghost,
  .btn-accent,
  .btn-outline-sm {
    min-height: 38px;
  }

  .btn-sm,
  .btn-ghost-small {
    min-height: 34px;
  }

  .summary-card {
    padding: 10px 12px;
  }
  .summary-num {
    font-size: 20px;
  }

  .table-card {
    overflow: visible;
  }

  .table-card > .data-table {
    min-width: 0;
  }

  .table-card > .data-table,
  .table-card > .data-table > thead,
  .table-card > .data-table > tbody,
  .table-card > .data-table > tbody > tr,
  .table-card > .data-table > tbody > tr > td {
    display: block;
  }

  .table-card > .data-table > thead {
    display: none;
  }

  .table-card > .data-table > tbody {
    display: grid;
    gap: 10px;
  }

  .dataset-row,
  .experiment-row {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: #fff;
    overflow: hidden;
  }

  .table-card > .data-table > tbody > tr > td {
    display: grid;
    grid-template-columns: 82px minmax(0, 1fr);
    align-items: center;
    gap: 10px;
    border-bottom: 1px solid #eef2f7;
    padding: 8px 10px;
    word-break: break-word;
  }

  .table-card > .data-table > tbody > tr > td:last-child {
    border-bottom: none;
  }

  .table-card > .data-table > tbody > tr > td::before {
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 700;
  }

  .dataset-row > td:nth-child(1)::before { content: '数据集'; }
  .dataset-row > td:nth-child(2)::before { content: '题目'; }
  .dataset-row > td:nth-child(3)::before { content: '创建'; }
  .dataset-row > td:nth-child(4)::before { content: '操作'; }

  .experiment-row > td:nth-child(1)::before { content: '数据集'; }
  .experiment-row > td:nth-child(2)::before { content: '策略'; }
  .experiment-row > td:nth-child(3)::before { content: '题目'; }
  .experiment-row > td:nth-child(4)::before { content: 'Top1'; }
  .experiment-row > td:nth-child(5)::before { content: 'Top3'; }
  .experiment-row > td:nth-child(6)::before { content: 'MRR'; }
  .experiment-row > td:nth-child(7)::before { content: '耗时'; }
  .experiment-row > td:nth-child(8)::before { content: '时间'; }
  .experiment-row > td:nth-child(9)::before { content: '操作'; }

  .questions-row {
    border: none;
    background: transparent;
  }

  .table-card > .data-table > tbody > .questions-row > td {
    display: block;
    padding: 0;
    border-bottom: none;
  }

  .table-card > .data-table > tbody > .questions-row > td::before {
    display: none;
  }

  .questions-panel {
    padding: 10px;
  }

  .questions-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .questions-actions {
    width: 100%;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }

  .questions-table {
    min-width: 0;
  }

  .questions-table,
  .questions-table thead,
  .questions-table tbody,
  .questions-table tr,
  .questions-table td {
    display: block;
  }

  .questions-table thead {
    display: none;
  }

  .questions-table tbody {
    display: grid;
    gap: 8px;
  }

  .questions-table tr {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: #fff;
    overflow: hidden;
  }

  .questions-table td {
    display: grid;
    grid-template-columns: 82px minmax(0, 1fr);
    gap: 8px;
    border-bottom: 1px solid #eef2f7;
    padding: 8px 10px;
  }

  .questions-table td:last-child {
    border-bottom: none;
  }

  .questions-table td::before {
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 700;
  }

  .questions-table td:nth-child(1)::before { content: '问题'; }
  .questions-table td:nth-child(2)::before { content: 'Chunk'; }
  .questions-table td:nth-child(3)::before { content: 'Golden Set'; }
  .questions-table td:nth-child(4)::before { content: 'Tags'; }
  .questions-table td:nth-child(5)::before { content: '操作'; }

  .questions-table td[colspan] {
    display: block;
  }

  .questions-table td[colspan]::before {
    display: none;
  }

  .eval-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .eval-card {
    padding: 12px;
  }

  .card-value {
    font-size: 22px;
  }

  .onboard-steps {
    flex-direction: column;
    gap: 10px;
  }

  .onboard-actions {
    display: grid;
    grid-template-columns: 1fr;
    align-items: stretch;
    gap: 8px;
  }

  .eval-onboard {
    padding: 22px 14px;
  }

  .onboard-desc br {
    display: none;
  }

  .bar-chart {
    gap: 10px;
    overflow-x: auto;
    justify-content: flex-start;
    padding-bottom: 4px;
  }

  .modal-detail {
    width: 100vw;
    max-height: 90vh;
    padding: 16px;
  }

  .modal-question,
  .modal-wide {
    width: 100vw;
    max-height: 92vh;
  }

  .per-question-table {
    max-height: 50vh;
    overflow: auto;
  }

  .ablation-toggle {
    padding: 10px;
    gap: 10px;
  }

  .actions-cell {
    justify-content: flex-start;
    flex-wrap: wrap;
    gap: 8px;
    white-space: normal;
  }

  .pager {
    display: grid;
    grid-template-columns: 1fr;
    justify-items: stretch;
    gap: 8px;
  }

  .pager-info {
    order: -1;
    text-align: center;
  }

  .chunk-detail-modal {
    width: 100vw;
    max-width: none;
  }

  .chunk-detail-head {
    flex-direction: column;
    gap: 8px;
  }

  .chunk-detail-close {
    width: 100%;
    min-height: 36px;
  }

  .chunk-detail-content {
    max-height: 58vh;
    padding: 10px;
  }

  .candidate-item {
    gap: 8px;
  }

  .candidate-head {
    flex-direction: column;
    gap: 2px;
  }

  .modal-actions {
    justify-content: stretch;
  }

  .modal-actions button {
    flex: 1;
  }
}

@media (max-width: 420px) {
  .summary-cards,
  .eval-metrics,
  .ab-param-grid {
    grid-template-columns: 1fr;
  }
}
</style>
