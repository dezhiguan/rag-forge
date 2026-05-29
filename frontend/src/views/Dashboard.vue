<template>
  <div>
    <div class="page-body">
      <div class="metrics-grid">
        <div class="metric-card">
          <div class="metric-label">知识库</div>
          <div class="metric-value">{{ metrics.kbCount ?? 0 }}</div>
          <div class="metric-desc">
            {{ formatNumber(metrics.documentCount ?? 0) }} 文档 · {{ formatChunkCount(metrics.chunkCount ?? 0) }} Chunk
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-label">检索命中率</div>
          <div class="metric-value metric-value--hit">{{ formatHitRate(metrics.hitRate) }}</div>
          <div class="metric-desc">最近一次评测实验 Top3</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">P95 延迟</div>
          <div class="metric-value">{{ formatLatency(metrics.avgLatencyMs) }}</div>
          <div class="metric-desc">今日平均检索延迟</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">今日 API 调用</div>
          <div class="metric-value">{{ formatNumber(metrics.todayApiCalls ?? 0) }}</div>
          <div class="metric-desc">来自 retrieval_logs</div>
        </div>
      </div>
      <div class="quick-actions">
        <div class="action-card primary" @click="$router.push('/knowledge')">
          <div class="action-icon">📁</div>
          <div class="action-text">管理知识库</div>
        </div>
        <div class="action-card" @click="$router.push('/debug')">
          <div class="action-icon">🔍</div>
          <div class="action-text">检索调试台</div>
        </div>
        <div class="action-card" @click="$router.push('/api')">
          <div class="action-icon">🔌</div>
          <div class="action-text">查看 API 文档</div>
        </div>
      </div>
      <div class="activity-panel">
        <div class="activity-title">最近操作</div>
        <div class="activity-list">
          <div
            class="activity-item"
            :class="{ error: item.type === 'error' }"
            v-for="(item, index) in metrics.recentActivities"
            :key="index"
          >
            {{ item.time }} {{ item.message }}
            <span
              v-if="item.retryable"
              class="link-action"
              @click="retryDocument(item.docId)"
            >重试</span>
          </div>
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
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}

.metric-card {
  background: #0f172a;
  border-radius: 10px;
  padding: 16px;
  color: #fff;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(15, 23, 42, 0.3);
}

.metric-label {
  font-size: 10px;
  opacity: 0.5;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 4px;
}

.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #fff;
  letter-spacing: -1px;
}

.metric-value--hit {
  color: #10b981;
}

.metric-desc {
  font-size: 10px;
  opacity: 0.5;
  margin-top: 2px;
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}

.action-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--light);
  border-radius: 8px;
  padding: 14px;
  border: none;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.action-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.action-card.primary {
  border: 2px solid #3b82f6;
  background: var(--light);
}

.action-icon {
  font-size: 24px;
  margin-bottom: 6px;
}

.action-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.activity-panel {
  background: var(--light);
  border-radius: 8px;
  padding: 12px;
}

.activity-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--text);
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.activity-item {
  font-size: 12px;
  color: #64748b;
}

.activity-item.error {
  color: #ef4444;
}

.link-action {
  margin-left: 6px;
  color: var(--blue);
  cursor: pointer;
}

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }

  .metric-card {
    padding: 12px;
  }

  .metric-value {
    font-size: 22px;
  }

  .quick-actions {
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
  }

  .action-card {
    padding: 10px 6px;
  }

  .action-icon {
    font-size: 20px;
  }

  .action-text {
    font-size: 11px;
  }
}
</style>
