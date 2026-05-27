<template>
  <div>
    <div class="page-body">
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
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
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
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th, .data-table td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--border); }
.data-table th { background: var(--light); font-weight: 600; color: var(--slate); font-size: 11px; text-transform: uppercase; }
.data-table tbody tr:hover { background: #f8fafc; }
.data-table td { color: var(--gray); }
</style>
