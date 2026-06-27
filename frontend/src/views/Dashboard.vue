<template>
  <div class="page-body">
    <Teleport to="#topbar-right">
      <span class="dash-updated">
        <span class="live-dot" />{{ lastUpdated ? `更新于 ${lastUpdated}` : '加载中…' }}
      </span>
      <button class="btn btn-secondary btn-sm" :disabled="loading" @click="loadMetrics">
        <span class="refresh-ico" :class="{ spin: loading }">↻</span>{{ loading ? '刷新中' : '刷新' }}
      </button>
    </Teleport>

    <div class="metrics-grid">
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">知识库</span>
          <span class="metric-chip chip-blue">📚</span>
        </div>
        <div class="metric-value">{{ metrics.kbCount ?? 0 }}</div>
        <div class="metric-desc">
          {{ formatNumber(metrics.documentCount ?? 0) }} 文档 · {{ formatChunkCount(metrics.chunkCount ?? 0) }} Chunk
        </div>
      </div>
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">最优 Top3 命中率</span>
          <span class="metric-chip chip-green">🎯</span>
        </div>
        <div class="metric-value metric-value--hit">{{ formatHitRate(metrics.hitRate) }}</div>
        <div class="metric-desc">当前数据集中最佳策略</div>
      </div>
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">今日平均延迟</span>
          <span class="metric-chip chip-amber">⚡</span>
        </div>
        <div class="metric-value">{{ formatLatency(metrics.avgLatencyMs) }}</div>
        <div class="metric-desc">所有检索请求均值</div>
      </div>
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">今日检索请求</span>
          <span class="metric-chip chip-cyan">📈</span>
        </div>
        <div class="metric-value">{{ formatNumber(metrics.todayApiCalls ?? 0) }}</div>
        <div class="metric-desc">/api/v1/search 调用次数</div>
      </div>
    </div>

    <div class="quick-actions">
      <div class="action-card primary" @click="$router.push('/knowledge')">
        <span class="action-chip">📁</span>
        <span class="action-text">管理知识库</span>
        <span class="action-arrow">→</span>
      </div>
      <div class="action-card" @click="$router.push('/debug')">
        <span class="action-chip">🔍</span>
        <span class="action-text">检索调试台</span>
        <span class="action-arrow">→</span>
      </div>
      <div class="action-card" @click="$router.push('/api-gateway')">
        <span class="action-chip">🔌</span>
        <span class="action-text">查看 API 文档</span>
        <span class="action-arrow">→</span>
      </div>
    </div>

    <div class="card activity-panel">
      <div class="card-header">
        <span class="card-title">最近操作</span>
      </div>
      <div class="activity-list">
        <div
          class="activity-item"
          :class="{ error: item.type === 'error' }"
          v-for="(item, index) in metrics.recentActivities"
          :key="index"
        >
          <span class="activity-dot" />
          <span class="activity-time">{{ item.time }}</span>
          <span class="activity-msg">{{ item.message }}</span>
          <button
            v-if="item.retryable"
            class="btn btn-ghost btn-sm activity-retry"
            @click="retryDocument(item.docId)"
          >重试</button>
        </div>
        <div v-if="!metrics.recentActivities || metrics.recentActivities.length === 0" class="activity-empty">
          暂无最近操作
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getDashboardMetrics } from '../api/metrics'
import { reprocessDocument } from '../api/document'

const loading = ref(false)
const lastUpdated = ref('')
const metrics = reactive({
  kbCount: 0,
  documentCount: 0,
  chunkCount: 0,
  todayApiCalls: 0,
  avgLatencyMs: 0,
  hitRate: 0,
  recentActivities: [],
})

async function loadMetrics() {
  loading.value = true
  try {
    const res = await getDashboardMetrics()
    const data = res.data ?? {}
    metrics.kbCount = data.kbCount ?? 0
    metrics.documentCount = data.documentCount ?? 0
    metrics.chunkCount = data.chunkCount ?? 0
    metrics.todayApiCalls = data.todayApiCalls ?? 0
    metrics.avgLatencyMs = data.avgLatencyMs ?? 0
    metrics.hitRate = data.hitRate ?? 0
    metrics.recentActivities = data.recentActivities ?? []
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } finally {
    loading.value = false
  }
}

async function retryDocument(docId) {
  if (!docId) {
    console.log('retryDocument: missing docId')
    return
  }
  await reprocessDocument(docId)
  await loadMetrics()
}

function formatNumber(n) {
  const num = Number(n)
  if (Number.isNaN(num)) return '0'
  return num.toLocaleString()
}

function formatChunkCount(n) {
  const num = Number(n)
  if (Number.isNaN(num)) return '0'
  if (num >= 1000) {
    const k = num / 1000
    return `${k.toFixed(k >= 100 ? 0 : 1)}K`
  }
  return `${num}`
}

function formatLatency(ms) {
  const num = Number(ms)
  if (Number.isNaN(num) || num <= 0) return '—'
  if (num >= 1000) return `${(num / 1000).toFixed(1)}s`
  return `${num}ms`
}

function formatHitRate(rate) {
  const num = Number(rate)
  if (Number.isNaN(num) || num <= 0) return '—'
  return `${(num * 100).toFixed(1)}%`
}

onMounted(loadMetrics)
</script>

<style scoped>
.refresh-ico { display: inline-block; font-size: 13px; }
.refresh-ico.spin { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 顶部状态条（teleport 到顶栏右侧） ===== */
.dash-updated {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.16);
}

/* ===== 指标卡 ===== */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.metric-card {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 18px 20px 20px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
/* 顶部极细高光，提升精致度 */
.metric-card::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: linear-gradient(90deg, transparent, rgba(37, 99, 235, 0.16), transparent);
  opacity: 0;
  transition: opacity 0.18s ease;
}
.metric-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-md);
  border-color: #d8e2ef;
}
.metric-card:hover::after { opacity: 1; }

.metric-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.metric-label { font-size: 12.5px; color: var(--text-muted); font-weight: 600; letter-spacing: 0.2px; }

.metric-chip {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}
.chip-blue { background: var(--primary-soft); box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.1); }
.chip-green { background: #ecfdf5; box-shadow: inset 0 0 0 1px rgba(16, 185, 129, 0.12); }
.chip-amber { background: #fffbeb; box-shadow: inset 0 0 0 1px rgba(245, 158, 11, 0.14); }
.chip-cyan { background: #ecfeff; box-shadow: inset 0 0 0 1px rgba(6, 182, 212, 0.14); }

.metric-value {
  font-size: 34px;
  font-weight: 700;
  color: var(--navy);
  letter-spacing: -0.8px;
  margin-top: 16px;
  line-height: 1.05;
  font-variant-numeric: tabular-nums;
}
.metric-value--hit { color: var(--green); }

.metric-desc { font-size: 12px; color: var(--text-muted); margin-top: 8px; }

/* ===== 快捷入口 ===== */
.quick-actions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 18px;
}

.action-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 15px 16px;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
}
.action-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--primary-border);
}
.action-card:hover .action-arrow { color: var(--primary); transform: translateX(2px); }

.action-chip {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: var(--primary-soft);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.action-card.primary { border-color: var(--primary-border); background: linear-gradient(180deg, #fff, #f7faff); }
.action-card.primary .action-chip { background: var(--primary); }

.action-text { font-size: 14px; font-weight: 600; color: var(--slate); flex: 1; }
.action-arrow { color: #cbd5e1; font-size: 16px; transition: color 0.15s ease, transform 0.15s ease; }

/* ===== 最近操作 ===== */
.activity-list { padding: 6px 8px 10px; }

.activity-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--gray);
  padding: 9px 12px;
  border-radius: 8px;
}
.activity-item:hover { background: #f8fafc; }

.activity-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-teal);
  flex-shrink: 0;
}
.activity-item.error .activity-dot { background: var(--red); }
.activity-item.error .activity-msg { color: var(--red); }

.activity-time { color: var(--text-muted); font-variant-numeric: tabular-nums; font-size: 12px; flex-shrink: 0; }
.activity-msg { flex: 1; min-width: 0; }
.activity-retry { margin-left: auto; color: var(--primary); }
.activity-retry:hover { background: var(--primary-soft); color: var(--primary-hover); }

.activity-empty { padding: 28px 0; text-align: center; color: var(--text-muted); font-size: 13px; }

/* ===== 移动端 ===== */
@media (max-width: 768px) {
  .metrics-grid { grid-template-columns: repeat(2, 1fr); gap: 12px; }
  .metric-value { font-size: 24px; }
  .quick-actions { grid-template-columns: 1fr; gap: 10px; }
}

@media (max-width: 420px) {
  .metrics-grid { grid-template-columns: 1fr; }
  .activity-time { display: none; }
}
</style>
