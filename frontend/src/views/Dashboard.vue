<template>
  <div>
    <div class="page-body">
      <div class="metrics-grid">
        <div class="metric-card">
          <div class="metric-label">知识库</div>
          <div class="metric-value">6</div>
          <div class="metric-desc">1,280 文档 · 52K Chunk</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">检索命中率</div>
          <div class="metric-value" style="color:#10b981;">91.2%</div>
          <div class="metric-desc">Hybrid + Reranker</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">P95 延迟</div>
          <div class="metric-value">2.8s</div>
          <div class="metric-desc">稳定 QPS 38</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">今日 API 调用</div>
          <div class="metric-value">3,240</div>
          <div class="metric-desc">¥0.008/次</div>
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
        <div class="activity-item">12:30 知识库「面试题库」索引重建完成</div>
        <div class="activity-item">11:45 评测实验「Hybrid+Reranker」完成，Top3: 89%</div>
        <div class="activity-item error">10:20 文档「某公司面经.pdf」解析失败 — <span class="link-action">重试</span></div>
      </div>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.metric-card {
  background: var(--navy);
  border-radius: 10px;
  padding: 16px;
  color: #fff;
  transition: transform 0.15s ease;
}
.metric-card:hover { transform: translateY(-2px); box-shadow: 0 8px 25px rgba(15,23,42,0.3); }
.metric-label { font-size: 10px; opacity: 0.5; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 4px; }
.metric-value { font-size: 28px; font-weight: 700; letter-spacing: -1px; }
.metric-desc { font-size: 10px; opacity: 0.5; margin-top: 2px; }

.quick-actions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.action-card {
  background: #fff;
  border-radius: 10px;
  padding: 20px;
  text-align: center;
  border: 2px solid var(--border);
  cursor: pointer;
  transition: all 0.15s ease;
}
.action-card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(0,0,0,0.06); }
.action-card.primary { border-color: var(--blue); }
.action-icon { font-size: 24px; margin-bottom: 6px; }
.action-text { font-weight: 600; font-size: 13px; }

.activity-panel {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
}
.activity-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; }
.activity-item { font-size: 12px; color: var(--text-muted); padding: 3px 0; }
.activity-item.error { color: var(--red); }
</style>
