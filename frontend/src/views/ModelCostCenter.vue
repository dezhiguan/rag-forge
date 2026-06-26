<template>
  <div class="page-body model-center">
    <div class="page-head">
      <div>
        <div class="sub">统一管理检索链路使用的大模型，监控 Token 消耗与费用</div>
      </div>
    </div>

    <div class="tabs">
      <div class="tab" :class="{ active: activeTab === 0 }" @click="activeTab = 0">模型管理</div>
      <div class="tab" :class="{ active: activeTab === 1 }" @click="activeTab = 1">成本看板</div>
      <div class="tab" :class="{ active: activeTab === 2 }" @click="activeTab = 2">
        用户配额<span class="tab-dot"></span>
      </div>
    </div>

    <!-- ============ 模型管理 ============ -->
    <section v-show="activeTab === 0">
      <div class="kpi-row">
        <div class="kpi">
          <div class="kpi-label">🧠 接入模型</div>
          <div class="kpi-val">{{ kpi.modelCount ?? 0 }}</div>
          <div class="kpi-delta">{{ kpi.enabledCount ?? 0 }} 启用 · {{ (kpi.modelCount ?? 0) - (kpi.enabledCount ?? 0) }} 停用</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">💰 本月费用</div>
          <div class="kpi-val">{{ formatMoney(kpi.monthlyCost) }}</div>
          <div class="kpi-delta">含输入与输出 Token</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">🔢 本月 Token</div>
          <div class="kpi-val">{{ formatTokens((kpi.monthlyInputTokens ?? 0) + (kpi.monthlyOutputTokens ?? 0)) }}</div>
          <div class="kpi-delta">输入 {{ formatTokens(kpi.monthlyInputTokens) }} · 输出 {{ formatTokens(kpi.monthlyOutputTokens) }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">📞 调用次数</div>
          <div class="kpi-val">{{ formatNum(kpi.callCount) }}</div>
          <div class="kpi-delta">成功率 {{ formatPct(kpi.successRate) }}</div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h3>已接入模型</h3>
          <span class="hint">开关即时生效 · 停用后该用途自动回退到备用模型</span>
        </div>
        <div v-if="loadingModels" class="state-hint"><span class="state-icon">⏳</span>加载中…</div>
        <table v-else class="data-table">
          <thead>
            <tr>
              <th>模型</th><th>用途</th><th>计价（¥/百万Token）</th>
              <th>本月费用</th><th>状态</th><th style="text-align:center">启用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in models" :key="m.code" :class="{ off: !m.enabled }">
              <td>
                <div class="m-name">{{ m.displayName }}</div>
                <div class="m-vendor">{{ vendorLabel(m.vendor) }}<span v-if="!m.isPrimary"> · 备用</span></div>
              </td>
              <td><span class="badge" :class="purposeClass(m.purpose)">{{ purposeLabel(m.purpose) }}</span></td>
              <td class="price">{{ priceLabel(m) }}</td>
              <td class="cost-cell">{{ formatMoney(m.monthlyCost) }}</td>
              <td>
                <span class="badge" :class="m.enabled ? 'badge-green' : 'badge-gray'">
                  {{ m.enabled ? '● 运行中' : '○ 已停用' }}
                </span>
              </td>
              <td style="text-align:center">
                <label class="sw" :class="{ disabled: toggling === m.code }">
                  <input type="checkbox" :checked="m.enabled" :disabled="toggling === m.code"
                         @change="onToggle(m, $event.target.checked)" />
                  <span class="track"></span><span class="thumb"></span>
                </label>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ============ 成本看板 ============ -->
    <section v-show="activeTab === 1">
      <div class="grid-2">
        <div class="card">
          <div class="card-head"><h3>近 7 日费用趋势</h3><span class="hint">按用途堆叠 · 单位 ¥</span></div>
          <div class="chart">
            <div class="bars">
              <div class="bar-col" v-for="p in trend" :key="p.date">
                <div class="stack" :style="{ height: barHeight(p) + '%' }">
                  <i v-for="seg in stackSegments(p)" :key="seg.purpose"
                     :style="{ height: seg.heightPct + '%', background: purposeColor(seg.purpose) }"
                     :title="purposeLabel(seg.purpose) + ' ¥' + seg.value"></i>
                </div>
                <div class="bar-x">{{ shortDate(p.date) }}</div>
              </div>
            </div>
            <div class="legend">
              <span v-for="pp in legendPurposes" :key="pp">
                <b :style="{ background: purposeColor(pp) }"></b>{{ purposeLabel(pp) }}
              </span>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-head"><h3>本月费用排行</h3><span class="hint">Top 模型</span></div>
          <div v-if="!ranking.length" class="state-hint"><span class="state-icon">📊</span>本月暂无费用数据</div>
          <div v-for="r in ranking" :key="r.modelCode" class="rank-item">
            <div class="rank-nm">
              <div class="rank-t">{{ r.modelCode }}</div>
              <div class="bar-mini"><i :style="{ width: r.pct + '%', background: 'var(--blue)' }"></i></div>
            </div>
            <div style="text-align:right">
              <div class="rank-amt">{{ formatMoney(r.cost) }}</div>
              <div class="rank-pct">{{ r.pct }}%</div>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head"><h3>调用明细（按用途）</h3><span class="hint">数据源：实时计量</span></div>
        <table class="data-table">
          <thead>
            <tr><th>用途环节</th><th>模型</th><th>调用次数</th><th>输入 Token</th><th>输出 Token</th><th>费用</th><th>平均延迟</th></tr>
          </thead>
          <tbody>
            <tr v-for="d in detail" :key="d.purpose + d.modelCode">
              <td><span class="badge" :class="purposeClass(d.purpose)">{{ purposeLabel(d.purpose) }}</span></td>
              <td class="price">{{ d.modelCode }}</td>
              <td>{{ formatNum(d.callCount) }}</td>
              <td>{{ formatTokens(d.inputTokens) }}</td>
              <td>{{ formatTokens(d.outputTokens) }}</td>
              <td class="cost-cell">{{ formatMoney(d.cost) }}</td>
              <td>{{ d.avgLatencyMs }} ms</td>
            </tr>
            <tr v-if="!detail.length"><td colspan="7" class="state-hint">本月暂无调用明细</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ============ 用户配额（Roadmap 占位） ============ -->
    <section v-show="activeTab === 2">
      <div class="note">🚧 用户配额功能待开发</div>
      <div class="roadmap">
        <div class="roadmap-ico">🚦</div>
        <div class="pill">ROADMAP · 多租户化阶段</div>
        <h3>用户配额管理</h3>
        <p>面向多租户演进时，将基于现有 <b>api_keys</b> 表为每个调用方分配独立的 Token / 费用 / QPS 配额，并在超额时熔断或降级。</p>
        <div class="feat-cards">
          <div class="fc"><div class="fi">📦</div><h4>配额套餐</h4><p>按 API Key 设置月度 Token 上限与费用预算</p></div>
          <div class="fc"><div class="fi">⛔</div><h4>超额策略</h4><p>软限流告警 / 硬熔断 / 自动降级到廉价模型</p></div>
          <div class="fc"><div class="fi">📈</div><h4>用量账单</h4><p>按租户出账，对接对账与计费系统</p></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getModelList, toggleModel, getModelCostStats, getModelCostDetail } from '../api/model'
import { useToast } from '../composables/useToast'

const toast = useToast()

const activeTab = ref(0)
const models = ref([])
const detail = ref([])
const stats = reactive({ kpi: {}, trend: [], ranking: [] })
const loadingModels = ref(false)
const toggling = ref('')

const kpi = computed(() => stats.kpi || {})
const trend = computed(() => stats.trend || [])
const ranking = computed(() => stats.ranking || [])

const PURPOSE_META = {
  EMBEDDING: { label: '向量化', cls: 'badge-embed', color: '#06b6d4' },
  JUDGE: { label: '评测/Judge', cls: 'badge-purpose', color: '#8b5cf6' },
  REWRITE: { label: 'Query 改写', cls: 'badge-purpose', color: '#3b82f6' },
  ANSWER: { label: '答案生成', cls: 'badge-purpose', color: '#6366f1' },
  RERANK: { label: '精排', cls: 'badge-rerank', color: '#10b981' },
  OCR: { label: '图片 OCR', cls: 'badge-ocr', color: '#f59e0b' },
}
const VENDOR_LABELS = { dashscope: '阿里云 DashScope', deepseek: 'DeepSeek', local: '本地微服务 · 自托管' }

function purposeLabel(p) { return PURPOSE_META[p]?.label || p }
function purposeClass(p) { return PURPOSE_META[p]?.cls || 'badge-gray' }
function purposeColor(p) { return PURPOSE_META[p]?.color || '#94a3b8' }
function vendorLabel(v) { return VENDOR_LABELS[v] || v }

const legendPurposes = computed(() => {
  const set = new Set()
  trend.value.forEach((p) => Object.keys(p.byPurpose || {}).forEach((k) => set.add(k)))
  return [...set]
})

function priceLabel(m) {
  if (m.isLocal) return '本地 · 无 API 费'
  if (Number(m.outputPrice) > 0) return `¥${fmt(m.inputPrice)} / ¥${fmt(m.outputPrice)}`
  return `¥${fmt(m.inputPrice)}`
}
function fmt(v) { return Number(v ?? 0).toFixed(2) }
function formatMoney(v) { return '¥' + Number(v ?? 0).toFixed(2) }
function formatNum(n) { return Number(n ?? 0).toLocaleString() }
function formatPct(r) { return (Number(r ?? 0) * 100).toFixed(1) + '%' }
function formatTokens(n) {
  const num = Number(n ?? 0)
  if (num >= 1e6) return (num / 1e6).toFixed(1) + 'M'
  if (num >= 1e3) return (num / 1e3).toFixed(1) + 'K'
  return String(num)
}
function shortDate(d) { return d ? d.slice(5) : d }

// 趋势柱：高度按当日总费用相对最大值缩放
const maxTrendTotal = computed(() => {
  let max = 0
  trend.value.forEach((p) => { max = Math.max(max, pointTotal(p)) })
  return max || 1
})
function pointTotal(p) {
  return Object.values(p.byPurpose || {}).reduce((s, v) => s + Number(v || 0), 0)
}
function barHeight(p) {
  const t = pointTotal(p)
  return t <= 0 ? 0 : Math.max(4, (t / maxTrendTotal.value) * 100)
}
function stackSegments(p) {
  const total = pointTotal(p)
  if (total <= 0) return []
  return Object.entries(p.byPurpose || {})
    .filter(([, v]) => Number(v) > 0)
    .map(([purpose, v]) => ({ purpose, value: Number(v).toFixed(2), heightPct: (Number(v) / total) * 100 }))
}

async function load() {
  loadingModels.value = true
  try {
    const [mRes, sRes, dRes] = await Promise.all([
      getModelList(),
      getModelCostStats(7),
      getModelCostDetail(),
    ])
    models.value = mRes.data ?? []
    const s = sRes.data ?? {}
    stats.kpi = s.kpi ?? {}
    stats.trend = s.trend ?? []
    stats.ranking = s.ranking ?? []
    detail.value = dRes.data ?? []
  } finally {
    loadingModels.value = false
  }
}

async function onToggle(model, nextEnabled) {
  if (toggling.value) return
  toggling.value = model.code
  try {
    await toggleModel(model.code, nextEnabled)
    model.enabled = nextEnabled
    toast.success(nextEnabled ? `已启用 ${model.displayName}` : `已停用 ${model.displayName}`)
    // 费用/状态可能变化，刷新看板侧数据
    refreshStats()
  } catch (e) {
    // 后端 409（停用会令该用途无可用模型）等错误已由 request 拦截器统一翻译并弹友好提示，
    // 这里只需把开关回滚：强制重渲染，让 checkbox 还原到 model.enabled 的真实值。
    models.value = [...models.value]
  } finally {
    toggling.value = ''
  }
}

async function refreshStats() {
  try {
    const [mRes, sRes] = await Promise.all([getModelList(), getModelCostStats(7)])
    models.value = mRes.data ?? []
    const s = sRes.data ?? {}
    stats.kpi = s.kpi ?? {}
    stats.ranking = s.ranking ?? []
  } catch { /* silent */ }
}

onMounted(load)
</script>

<style scoped>
.model-center { font-size: 14px; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 4px; }
.page-head .sub { color: var(--text-muted); font-size: 13px; }

.tabs { display: flex; gap: 4px; margin: 16px 0 18px; border-bottom: 1px solid var(--border); }
.tab { padding: 9px 16px; font-size: 14px; color: var(--text-muted); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: var(--blue); border-bottom-color: var(--blue); font-weight: 600; }
.tab-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--amber); margin-left: 5px; vertical-align: middle; }

.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 20px; }
.kpi { background: #fff; border: 1px solid var(--border); border-radius: 12px; padding: 16px 18px; }
.kpi-label { font-size: 12.5px; color: var(--text-muted); }
.kpi-val { font-size: 26px; font-weight: 800; color: var(--navy); margin-top: 6px; letter-spacing: -.5px; }
.kpi-delta { font-size: 12px; margin-top: 5px; color: var(--text-muted); }

.card { background: #fff; border: 1px solid var(--border); border-radius: 12px; margin-bottom: 20px; }
.card-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--border); }
.card-head h3 { font-size: 15px; font-weight: 700; color: var(--slate); }
.card-head .hint { font-size: 12px; color: var(--text-muted); }

.data-table { width: 100%; border-collapse: collapse; font-size: 13.5px; }
.data-table th { text-align: left; padding: 11px 18px; background: #f8fafc; color: var(--text-muted); font-size: 11px; font-weight: 600; border-bottom: 1px solid var(--border); }
.data-table td { padding: 13px 18px; border-bottom: 1px solid var(--border); color: var(--gray); vertical-align: middle; }
.data-table tr:last-child td { border-bottom: none; }
tr.off td { opacity: .5; }
.m-name { font-weight: 700; color: var(--navy); font-size: 14px; }
.m-vendor { font-size: 11.5px; color: var(--text-muted); margin-top: 1px; }
.price { color: var(--slate); font-variant-numeric: tabular-nums; }
.cost-cell { font-weight: 700; color: var(--navy); font-variant-numeric: tabular-nums; }

.badge-embed { background: #cffafe; color: #0e7490; }
.badge-rerank { background: #dcfce7; color: #15803d; }
.badge-ocr { background: #fef3c7; color: #92400e; }
.badge-purpose { background: #ede9fe; color: #6d28d9; }
.badge-gray { background: #e2e8f0; color: #475569; }

.sw { position: relative; display: inline-block; width: 40px; height: 22px; cursor: pointer; }
.sw.disabled { opacity: .5; cursor: not-allowed; }
.sw input { display: none; }
.sw .track { position: absolute; inset: 0; background: #cbd5e1; border-radius: 22px; transition: .2s; }
.sw .thumb { position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; background: #fff; border-radius: 50%; transition: .2s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.sw input:checked + .track { background: var(--green); }
.sw input:checked + .track + .thumb { transform: translateX(18px); }

.grid-2 { display: grid; grid-template-columns: 1.6fr 1fr; gap: 20px; }
.chart { padding: 18px; }
.bars { display: flex; align-items: flex-end; gap: 10px; height: 190px; padding-top: 8px; }
.bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px; justify-content: flex-end; height: 100%; }
.stack { width: 58%; min-width: 16px; display: flex; flex-direction: column; justify-content: flex-end; border-radius: 5px 5px 0 0; overflow: hidden; }
.stack i { display: block; }
.bar-x { font-size: 11px; color: var(--text-muted); }
.legend { display: flex; gap: 16px; margin-top: 14px; font-size: 12px; color: var(--gray); flex-wrap: wrap; }
.legend span { display: flex; align-items: center; gap: 6px; }
.legend b { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }

.bar-mini { height: 5px; border-radius: 3px; background: var(--light); margin-top: 5px; overflow: hidden; }
.bar-mini i { display: block; height: 100%; border-radius: 3px; }
.rank-item { display: flex; align-items: center; gap: 12px; padding: 12px 18px; border-bottom: 1px solid var(--border); }
.rank-item:last-child { border-bottom: none; }
.rank-nm { flex: 1; }
.rank-t { font-weight: 600; color: var(--slate); font-size: 13.5px; }
.rank-amt { font-weight: 700; color: var(--navy); font-variant-numeric: tabular-nums; font-size: 14px; }
.rank-pct { font-size: 11px; color: var(--text-muted); }

.note { background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px; padding: 13px 18px; font-size: 12.5px; color: #92400e; margin-bottom: 20px; }
.roadmap { background: #fff; border: 1.5px dashed #cbd5e1; border-radius: 14px; padding: 40px; text-align: center; }
.roadmap-ico { font-size: 38px; opacity: .8; margin-bottom: 12px; }
.roadmap .pill { display: inline-block; background: #eff6ff; color: var(--blue); border: 1px solid #bfdbfe; border-radius: 20px; padding: 5px 14px; font-size: 12px; font-weight: 600; margin-bottom: 20px; }
.roadmap h3 { font-size: 18px; color: var(--slate); margin-bottom: 8px; }
.roadmap p { color: var(--text-muted); max-width: 560px; margin: 0 auto 22px; font-size: 13.5px; }
.feat-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; max-width: 680px; margin: 0 auto; text-align: left; }
.feat-cards .fc { background: #f8fafc; border: 1px solid var(--border); border-radius: 10px; padding: 16px; }
.feat-cards .fc .fi { font-size: 18px; margin-bottom: 8px; }
.feat-cards .fc h4 { font-size: 13.5px; color: var(--slate); margin-bottom: 4px; }
.feat-cards .fc p { font-size: 12px; color: var(--text-muted); margin: 0; }

@media (max-width: 768px) {
  .kpi-row { grid-template-columns: repeat(2, 1fr); }
  .grid-2 { grid-template-columns: 1fr; }
  .feat-cards { grid-template-columns: 1fr; }
}
</style>
