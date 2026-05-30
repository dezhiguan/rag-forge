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

        <div class="top-toolbar">
          <div class="toolbar-left">
            <button class="btn-primary" @click="openCreateDataset">+ 创建数据集</button>
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
            <button class="btn-primary" @click="openCreateDataset">+ 创建数据集</button>
            <button class="btn-accent" @click="openQuickStart('resume')">⚡ 快速体验（自动弱标注）</button>
            <button class="btn-accent" @click="openQuickStart('extreme')">🧪 极限测试用例</button>
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
                    <span class="link-action danger-subtle" @click.stop="onDeleteDataset(ds)">删除</span>
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
                            <th style="width: 50%;">问题</th>
                            <th>期望 Chunk IDs</th>
                            <th style="width: 80px;">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-if="!(questionsMap[ds.id]?.list || []).length">
                            <td colspan="3">
                              <div class="state-hint" style="padding:24px 0">
                                <div class="state-icon">📝</div>
                                <div class="state-desc">点击「添加题目」或「批量导入」</div>
                              </div>
                            </td>
                          </tr>
                          <tr v-for="q in questionsMap[ds.id]?.list || []" :key="q.id">
                            <td class="question-text">{{ q.question }}</td>
                            <td class="chunk-ids" :title="formatChunkIds(q.expectedChunkIds)">
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
        <!-- 概览卡片 -->
        <div class="summary-cards" v-if="experiments.length">
          <div class="summary-card">
            <div class="summary-num">{{ experiments.length }}</div>
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
            <button class="btn-primary" @click="openRunExperiment">+ 运行新实验</button>
            <button class="btn-ghost btn-sm" :disabled="loadingExperiments" @click="loadExperiments">刷新</button>
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
            <button class="btn-ghost" @click="showCreateDataset = false">取消</button>
            <button class="btn-primary" :disabled="submittingDataset" @click="onCreateDataset">
              确定
            </button>
          </div>
        </div>
      </div>

      <div v-if="showAddQuestion" class="modal-mask" @click.self="showAddQuestion = false">
        <div class="modal modal-question">
          <h3 class="modal-title">添加题目</h3>
          <template v-if="addQuestionStep === 1">
            <label class="field">
              <span>问题 *</span>
              <textarea v-model="questionForm.question" rows="3" placeholder="输入评测问题" />
            </label>
            <div class="modal-actions">
              <button class="btn-ghost" @click="showAddQuestion = false">取消</button>
              <button class="btn-primary" :disabled="searchingCandidates" @click="onSearchCandidates">
                {{ searchingCandidates ? '检索中…' : '🔍 检索候选 Chunk' }}
              </button>
            </div>
          </template>

          <template v-else>
            <div class="field">
              <span>问题</span>
              <div class="question-preview">{{ questionForm.question }}</div>
            </div>
            <div class="selected-count">已选 {{ selectedChunkIds.length }} 个 chunk</div>
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
              <button class="btn-ghost" @click="addQuestionStep = 1">上一步</button>
              <button class="btn-primary" :disabled="submittingQuestion" @click="onAddQuestion">
                {{ submittingQuestion ? '保存中…' : '确认保存' }}
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
            <button class="btn-ghost" @click="showRunExperiment = false">取消</button>
            <button class="btn-primary" :disabled="runningExperiment" @click="onRunExperiment">
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
          <h3 class="modal-title">{{ quickStartPreset === 'extreme' ? '🧪 极限测试用例' : '⚡ 快速体验 — 自动弱标注简历用例' }}</h3>
          <div class="field">
            <span>选择知识库（包含你简历的 KB）*</span>
            <select v-model="quickStartKbId" class="select">
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
            预置题目（{{ currentQuickStartQuestions.length }} 道）：
          </div>
          <div class="quick-questions-list">
            <div v-for="(q, i) in currentQuickStartQuestions" :key="i" class="quick-q-item">
              <span class="quick-q-num">{{ i + 1 }}</span>
              <span>{{ q }}</span>
            </div>
          </div>
          <div class="field" style="margin-top:12px;">
            <span style="color:var(--text-muted);font-size:11px;">
              {{ creatingQuickStart ? '正在逐题检索并自动标注期望 Chunk…' : '题目导入后自动检索知识库，取 Top-3 作为期望 Chunk' }}
            </span>
          </div>
          <div class="modal-actions">
            <button class="btn-ghost" @click="showQuickStart = false">取消</button>
            <button class="btn-accent" :disabled="creatingQuickStart" @click="onCreateQuickStart">
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
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
  runExperiment,
} from '../api/eval'
import { listKb } from '../api/kb'
import { search as searchApi } from '../api/search'

const route = useRoute()

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
const addQuestionStep = ref(1)
const questionForm = ref({ question: '' })
const candidateChunks = ref([])
const selectedChunkIds = ref([])

const showQuickStart = ref(false)
const creatingQuickStart = ref(false)
const quickStartKbId = ref(null)
const quickStartName = ref('')
const quickStartPreset = ref('resume')

const quickStartQuestions = [
  '官德志在影子科技主导了什么核心架构设计？',
  'B2B抢购系统中是如何解决高并发超卖问题的？',
  'CountDownLatch在海量数据平台中起到了什么关键作用？',
  '边缘端运行时为什么要从Java重构到Go语言？',
  'AI协同开发模式中，SDD（标准化开发规范）解决了什么问题？',
  '官德志在铂涛信息公司的年度绩效评价如何？',
  'ELK+SkyWalking全链路监控体系带来了什么具体效果？',
  'RocketMQ事务消息在智能体平台中解决了什么分布式难题？',
]

const extremeQuestions = [
  // 一、语义理解类
  '小关在影子科技的时候，是怎么防止秒杀系统超卖的？',
  '那个让人等齐了再一起跑的 Java 工具，在海量数据项目里怎么用的？',
  '如果不把边缘端从 Java 改成 Go，会有什么后果？',
  // 二、关键词匹配类
  'ELK 那套东西加上 SkyWalking，到底解决了啥实际问题？',
  'RocketMQ 的 transactional message 在 agent 平台里解决的是什么 distributed 问题？',
  '官德志用过哪些中间件？给我全部列出来。',
  // 三、精确匹配类
  '官德志在铂涛信息的年度绩效是什么等级？',
  'SDD 是什么，解决了什么问题？',
  // 四、负样本
  '官德志用过 Python 做机器学习吗？',
  '官德志在阿里巴巴工作的时候负责什么项目？',
  '官德志会前端开发吗？',
  // 五、多 chunk 综合类
  '官德志在影子科技做的所有项目，分别用了哪些技术方案？',
  '官德志从哪年开始工作，各段经历的时间顺序是怎样的？',
  // 六、Input 鲁棒性
  '高并发',
  '我最近在面一家做企业级 SaaS 的公司，他们技术负责人问我有没有做过大规模数据处理和高并发系统的经验，我想知道官德志的简历里有哪些相关经验可以借鉴参考？',
]

const currentQuickStartQuestions = computed(() =>
  quickStartPreset.value === 'extreme' ? extremeQuestions : quickStartQuestions
)

const showBatchImport = ref(false)
const submittingBatch = ref(false)
const batchDatasetId = ref(null)
const batchText = ref('')

const experiments = ref([])
const loadingExperiments = ref(false)
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

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const res = await listEvalDatasets()
    datasets.value = res.data ?? []
  } finally {
    loadingDatasets.value = false
  }
}

async function loadExperiments() {
  loadingExperiments.value = true
  try {
    const res = await listExperiments()
    experiments.value = res.data ?? []
  } finally {
    loadingExperiments.value = false
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
  addQuestionStep.value = 1
  questionForm.value = { question: '' }
  candidateChunks.value = []
  selectedChunkIds.value = []
  showAddQuestion.value = true
}

async function onSearchCandidates() {
  if (!questionForm.value.question?.trim()) {
    alert('请先填写问题')
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
    selectedChunkIds.value = []
    addQuestionStep.value = 2
  } catch {
    alert('检索候选 Chunk 失败，请稍后重试')
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
    alert('请填写问题')
    return
  }
  submittingQuestion.value = true
  try {
    await createEvalQuestion(addQuestionDatasetId.value, {
      question: questionForm.value.question.trim(),
      expectedChunkIds: selectedChunkIds.value,
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

function openQuickStart(preset) {
  quickStartPreset.value = preset || 'resume'
  quickStartKbId.value = kbList.value.length ? kbList.value[0].id : null
  quickStartName.value = preset === 'extreme' ? '极限检索评测集' : '简历检索评测集'
  showQuickStart.value = true
}

async function onCreateQuickStart() {
  if (!quickStartKbId.value) {
    alert('请选择知识库')
    return
  }
  if (!quickStartName.value?.trim()) {
    alert('请填写数据集名称')
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
      alert('数据集创建失败')
      return
    }

    // 2. 逐题检索，自动取 Top-3 作为期望 Chunk
    const questionsWithChunks = []
    for (const question of currentQuickStartQuestions.value) {
      try {
        const res = await searchApi({
          query: question,
          strategy: 'full',
          topK: 8,
          kbIds: [quickStartKbId.value],
          rerankTopN: 5,
        })
        const top3Ids = (res.data?.results ?? [])
          .slice(0, 3)
          .map((r) => r.chunkId)
          .filter((id) => id != null)
        questionsWithChunks.push({ question, expectedChunkIds: top3Ids })
      } catch {
        // 检索失败也创建，只是没有期望 Chunk
        questionsWithChunks.push({ question, expectedChunkIds: [] })
      }
    }

    // 3. 批量导入
    await batchCreateEvalQuestions(datasetId, questionsWithChunks)
    showQuickStart.value = false
    await loadDatasets()
    if (datasetId) {
      expandedDatasetId.value = datasetId
      await loadQuestions(datasetId, 1)
    }
  } catch (e) {
    alert('创建失败：' + (e?.response?.data?.message || e?.message || '未知错误'))
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
    alert('请选择数据集')
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

async function openExperimentDetail(id) {
  const res = await getExperiment(id)
  experimentDetail.value = res.data ?? null
  showExperimentDetail.value = true
}

async function onDeleteExperiment(exp) {
  if (!confirm(`确定删除实验 #${exp.id}？`)) return
  await deleteExperiment(exp.id)
  await loadExperiments()
}

function formatTime(iso) {
  if (!iso) return '-'
  return iso.toString().replace('T', ' ').slice(0, 19)
}

function formatChunkIds(ids) {
  if (!ids?.length) return '—'
  return ids.join(', ')
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
  if (!item.expectedChunkIds?.length) return `未标注标准 Chunk，召回 ${recalledCount} 条`
  if (!item.recalledChunkIds?.length) return '没有召回结果'
  if (item.expectedBestRank != null) {
    return `标准 Chunk 在第 ${item.expectedBestRank} 位，未进入 Top3`
  }
  return `标准 Chunk 未进入 TopK，召回 ${recalledCount} 条`
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
  background: var(--blue);
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
  color: var(--blue);
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
  color: var(--blue);
  border-bottom-color: var(--blue);
}

.top-toolbar { margin-bottom: 12px; }
.toolbar-left { display: flex; gap: 10px; align-items: center; }

.btn-primary {
  background: var(--blue);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }

.btn-ghost {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text);
}
.btn-ghost:disabled { opacity: 0.5; }

.btn-sm { padding: 6px 10px; font-size: 12px; }
.btn-ghost-small { padding: 6px 10px; font-size: 12px; }

.btn-outline-sm {
  display: inline-block;
  padding: 4px 10px;
  border: 1px solid var(--blue);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--blue);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-outline-sm:hover { background: #eff6ff; }

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
  border-radius: var(--radius-md);
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
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
.metric-accent { color: #10b981; }

/* Actions cell */
.actions-cell { display: flex; align-items: center; gap: 12px; white-space: nowrap; }

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
.ablation-toggle:hover { border-color: var(--blue); }
.toggle-track {
  width: 40px;
  height: 22px;
  border-radius: 11px;
  background: #cbd5e1;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}
.toggle-track.on { background: var(--blue); }
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
.advanced-toggle:hover { color: var(--blue); }


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
.bar.bar-best { background: var(--blue); }
.bar-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--slate);
  margin-top: 8px;
  white-space: nowrap;
}
.bar-pct { font-size: 10px; color: var(--blue); font-weight: 600; }

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
  .summary-cards {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .tab-bar {
    position: sticky;
    top: 0;
    z-index: 6;
    margin: -16px -12px 14px;
    padding: 8px 12px 0;
    background: #fafbfc;
  }

  .tab-btn {
    flex: 1;
    min-height: 40px;
    padding: 8px 10px;
    font-size: 13px;
  }

  .top-toolbar,
  .toolbar-left {
    width: 100%;
  }

  .toolbar-left {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 78px;
    gap: 8px;
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
  .questions-table td:nth-child(3)::before { content: '操作'; }

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
  .eval-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
