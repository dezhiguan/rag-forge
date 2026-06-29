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

    <div v-if="isPlatform" class="breakglass-banner">
      <span class="bg-ico">🛡</span>
      <span class="bg-txt">
        <b>破玻璃提权已激活（X-Admin-Override）</b> —— 全平台聚合视图，本次访问全程审计留痕；SYSTEM 库与明文凭证不可得。
      </span>
    </div>

    <div class="metrics-grid">
      <!-- 资产规模 -->
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">资产规模</span>
          <span class="metric-chip chip-blue">📚</span>
        </div>
        <div class="metric-value">{{ formatNumber(metrics.chunkCount) }} <span class="metric-unit">Chunk</span></div>
        <div class="metric-subs">
          <div class="metric-sub"><span>文档</span><b>{{ formatNumber(metrics.documentCount) }}</b></div>
          <div class="metric-sub"><span>知识库</span><b>{{ metrics.kbCount }}</b></div>
        </div>
      </div>
      <!-- 检索质量 -->
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">检索质量</span>
          <span class="metric-chip chip-green">🎯</span>
        </div>
        <div class="metric-value metric-value--hit">{{ formatPercent(metrics.zeroResultRate) }}<span class="metric-unit"> 零结果</span></div>
        <div class="metric-subs">
          <div class="metric-sub"><span>平均召回条数</span><b>{{ formatDecimal(metrics.avgRecallCount) }}</b></div>
          <div class="metric-sub"><span>Query 改写率</span><b>{{ formatPercent(metrics.rewriteRate) }}</b></div>
        </div>
      </div>
      <!-- 运行健康 -->
      <div class="metric-card">
        <div class="metric-top">
          <span class="metric-label">运行健康</span>
          <span class="metric-chip chip-amber">⚡</span>
        </div>
        <div class="metric-value">{{ formatLatency(metrics.p95LatencyMs) }} <span class="metric-unit">P95</span></div>
        <div class="metric-subs">
          <div class="metric-sub"><span>平均延迟</span><b>{{ formatLatency(metrics.avgLatencyMs) }}</b></div>
          <div class="metric-sub"><span>今日检索请求</span><b>{{ formatNumber(metrics.todayApiCalls) }}</b></div>
        </div>
      </div>
      <!-- 成本消耗（明细见模型&成本中心） -->
      <div class="metric-card metric-card--link" @click="$router.push('/models')">
        <div class="metric-top">
          <span class="metric-label">成本消耗</span>
          <span class="metric-chip chip-violet">💰</span>
        </div>
        <div class="metric-value metric-value--muted">查看明细</div>
        <div class="metric-desc">模型 &amp; 成本中心 →</div>
      </div>
    </div>

    <div class="quick-actions">
      <div class="action-card primary" @click="$router.push({ path: '/knowledge', query: { create: 1 } })">
        <span class="action-chip">＋</span>
        <span class="action-text">新建知识库</span>
        <span class="action-arrow">→</span>
      </div>
      <div class="action-card" @click="$router.push('/uploads/wizard')">
        <span class="action-chip">⬆</span>
        <span class="action-text">上传文档</span>
        <span class="action-arrow">→</span>
      </div>
      <div class="action-card" @click="$router.push('/debug')">
        <span class="action-chip">▷</span>
        <span class="action-text">发起检索测试</span>
        <span class="action-arrow">→</span>
      </div>
    </div>

    <div v-if="!isPersonal && !isPlatform" class="card member-panel">
      <div class="card-header">
        <span class="card-title">组织成员</span>
        <button class="btn btn-secondary btn-sm" @click="$router.push('/orgs')">管理成员 →</button>
      </div>
      <div class="member-overview">
        <div class="ava-pile">
          <span
            v-for="m in members.slice(0, 6)"
            :key="m.userId"
            class="m-ava"
            :style="{ background: avaColor(m.userId) }"
            :title="m.displayName"
          >{{ initial(m.displayName) }}</span>
          <span v-if="memberStats.total > 6" class="m-ava ava-more">+{{ memberStats.total - 6 }}</span>
        </div>
        <div class="member-stats">
          <span>共 <b>{{ memberStats.total }}</b> 人</span>
          <span class="sep">·</span>
          <span>{{ memberStats.owner }} OWNER</span>
          <span>{{ memberStats.admin }} ADMIN</span>
          <span>{{ memberStats.member }} MEMBER</span>
        </div>
      </div>
    </div>

    <div v-if="isPlatform" class="card perm-panel">
      <div class="card-header">
        <span class="card-title">平台管理员权限模型</span>
        <span class="card-sub">最小权限 + 破玻璃 + 强制审计</span>
      </div>
      <table class="perm-table">
        <thead><tr><th>能力</th><th>默认 ADMIN</th><th>破玻璃后</th><th>说明</th></tr></thead>
        <tbody>
          <tr><td>看自己的库/数据</td><td class="yes">✓</td><td class="yes">✓</td><td>同普通用户</td></tr>
          <tr><td>全平台聚合指标</td><td class="no">✗</td><td class="yes">✓</td><td>默认只看自己</td></tr>
          <tr><td>跨组织读他人私库内容</td><td class="no">✗</td><td class="yes">✓</td><td>写审计留痕</td></tr>
          <tr><td>SYSTEM 库</td><td class="no">✗</td><td class="no">✗</td><td>永不暴露</td></tr>
          <tr><td>危险操作（删组织/转 OWNER/封号）</td><td class="no">✗</td><td class="cond">破玻璃+二次确认</td><td>建议补</td></tr>
          <tr><td>明文手机号 / 凭证</td><td class="no">✗</td><td class="no">✗</td><td>网关只存哈希</td></tr>
        </tbody>
      </table>
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
import { onMounted, reactive, ref, computed } from 'vue'
import { getDashboardMetrics } from '../api/metrics'
import { reprocessDocument } from '../api/document'
import { useOrg } from '../composables/useOrg'
import { listMembers } from '../api/org'

const { current, isPersonal, isPlatform } = useOrg()
const loading = ref(false)
const lastUpdated = ref('')

// ===== 组织成员概览（仅团队组织展示，复用 /orgs/{id}/members） =====
const members = ref([])
const memberStats = computed(() => {
  const s = { total: members.value.length, owner: 0, admin: 0, member: 0 }
  for (const m of members.value) {
    if (m.role === 'OWNER') s.owner += 1
    else if (m.role === 'ADMIN') s.admin += 1
    else s.member += 1
  }
  return s
})
const MEMBER_PALETTE = ['#0f1f3d', '#2563eb', '#15803d', '#7c3aed', '#db2777', '#0ea5e9', '#f59e0b']
function avaColor(id) {
  return MEMBER_PALETTE[Math.abs(Number(id) || 0) % MEMBER_PALETTE.length]
}
function initial(name) {
  return (name || '?').trim().charAt(0).toUpperCase()
}
async function loadMembers() {
  if (isPersonal.value || isPlatform.value || !current.value.id) {
    members.value = []
    return
  }
  try {
    const res = await listMembers(current.value.id)
    members.value = Array.isArray(res?.data) ? res.data : []
  } catch {
    members.value = []
  }
}
const metrics = reactive({
  kbCount: 0,
  documentCount: 0,
  chunkCount: 0,
  todayApiCalls: 0,
  avgLatencyMs: 0,
  hitRate: 0,
  zeroResultRate: 0,
  avgRecallCount: 0,
  p95LatencyMs: 0,
  rewriteRate: 0,
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
    metrics.zeroResultRate = data.zeroResultRate ?? 0
    metrics.avgRecallCount = data.avgRecallCount ?? 0
    metrics.p95LatencyMs = data.p95LatencyMs ?? 0
    metrics.rewriteRate = data.rewriteRate ?? 0
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

function formatPercent(rate) {
  const num = Number(rate)
  if (Number.isNaN(num) || num < 0) return '—'
  return `${(num * 100).toFixed(1)}%`
}

function formatDecimal(n) {
  const num = Number(n)
  if (Number.isNaN(num) || num <= 0) return '—'
  return num.toFixed(1)
}

onMounted(() => {
  loadMetrics()
  loadMembers()
})
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
.chip-violet { background: #f5f3ff; box-shadow: inset 0 0 0 1px rgba(124, 58, 237, 0.14); }

/* 四象限：主数值单位 + 次级统计行 */
.metric-unit { font-size: 14px; font-weight: 600; color: var(--text-muted); }
.metric-value--muted { color: var(--text-muted); font-size: 22px; }
.metric-subs { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--border); display: flex; flex-direction: column; gap: 7px; }
.metric-sub { display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: var(--text-muted); }
.metric-sub b { color: var(--slate); font-weight: 600; font-variant-numeric: tabular-nums; }
.metric-card--link { cursor: pointer; }

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

/* ===== 破玻璃横幅 + 权限矩阵（全平台视图） ===== */
.breakglass-banner { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; border-radius: 14px; padding: 12px 16px; background: linear-gradient(95deg, #fff7ed, #fffbeb); border: 1px solid #fed7aa; }
.breakglass-banner .bg-ico { width: 34px; height: 34px; border-radius: 10px; background: var(--amber, #f59e0b); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 17px; flex-shrink: 0; }
.breakglass-banner .bg-txt { font-size: 13px; color: var(--slate); } .breakglass-banner .bg-txt b { color: var(--navy); }
.card-sub { font-size: 12px; color: var(--text-muted); }
.perm-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.perm-table th, .perm-table td { text-align: left; padding: 9px 16px; border-top: 1px solid var(--border); }
.perm-table th { color: var(--text-muted); font-weight: 600; font-size: 11.5px; }
.perm-table td { color: var(--slate); }
.perm-table .yes { color: #10b981; font-weight: 700; }
.perm-table .no { color: #cbd5e1; font-weight: 700; }
.perm-table .cond { color: #f59e0b; font-weight: 600; font-size: 11.5px; }

/* ===== 组织成员概览（定高，不随人数变长） ===== */
.member-panel .card-header { display: flex; align-items: center; justify-content: space-between; }
.member-overview { padding: 12px 16px 16px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.ava-pile { display: flex; }
.ava-pile .m-ava { margin-left: -10px; border: 2px solid #fff; }
.ava-pile .m-ava:first-child { margin-left: 0; }
.m-ava { width: 34px; height: 34px; border-radius: 9px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 700; }
.ava-more { background: #e2e8f0; color: var(--gray); font-size: 12px; }
.member-stats { font-size: 13px; color: var(--text-muted); display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.member-stats b { color: var(--navy); font-weight: 700; }
.member-stats .sep { color: #dbe2ea; }

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
