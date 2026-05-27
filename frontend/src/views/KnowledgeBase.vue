<template>
  <div>
    <div class="page-body">
      <div class="upload-zone">
        <div class="upload-icon">📤</div>
        <div class="upload-text">拖拽文件上传 · 支持 PDF / Markdown / Word / HTML</div>
        <div class="upload-hint">单文件最大 50MB · 支持批量上传</div>
      </div>
      <div class="pipeline">
        <div class="pipe-item active">
          <div class="pipe-icon">📥</div><div class="pipe-name">解析</div><div class="pipe-desc">PDF→Text</div>
        </div>
        <span class="pipe-arrow">→</span>
        <div class="pipe-item done">
          <div class="pipe-icon">✂️</div><div class="pipe-name">分块</div><div class="pipe-desc">512 tokens</div>
        </div>
        <span class="pipe-arrow">→</span>
        <div class="pipe-item">
          <div class="pipe-icon">🧮</div><div class="pipe-name">向量化</div><div class="pipe-desc">Embedding</div>
        </div>
        <span class="pipe-arrow">→</span>
        <div class="pipe-item">
          <div class="pipe-icon">📇</div><div class="pipe-name">BM25</div><div class="pipe-desc">ES 索引</div>
        </div>
        <span class="pipe-arrow">→</span>
        <div class="pipe-item">
          <div class="pipe-icon">✅</div><div class="pipe-name">可用</div><div class="pipe-desc">可检索</div>
        </div>
      </div>
      <table class="data-table">
        <thead>
          <tr><th>知识库</th><th>文档</th><th>Chunk</th><th>Embedding</th><th>BM25</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>🧑 求职者简历库</strong></td><td>3</td><td>420</td><td>text-embedding-3</td><td>✓</td>
            <td><span class="badge badge-green">可用</span></td>
            <td><span class="link-action" @click="$router.push('/document/1')">详情</span> · <span class="link-action" @click="$router.push('/debug')">检索</span></td>
          </tr>
          <tr>
            <td><strong>💼 岗位 JD 库</strong></td><td>156</td><td>8,200</td><td>text-embedding-3</td><td>✓</td>
            <td><span class="badge badge-green">可用</span></td>
            <td><span class="link-action" @click="$router.push('/document/1')">详情</span> · <span class="link-action" @click="$router.push('/debug')">检索</span></td>
          </tr>
          <tr>
            <td><strong>📝 面试题库</strong></td><td>420</td><td>18,500</td><td>text-embedding-3</td><td>✓</td>
            <td><span class="badge badge-amber">重建中</span></td>
            <td><span class="link-action">查看任务</span></td>
          </tr>
          <tr>
            <td><strong>🏢 公司信息库</strong></td><td>89</td><td>6,100</td><td>text-embedding-3</td><td>✓</td>
            <td><span class="badge badge-green">可用</span></td>
            <td><span class="link-action" @click="$router.push('/document/1')">详情</span> · <span class="link-action" @click="$router.push('/debug')">检索</span></td>
          </tr>
          <tr>
            <td><strong>📚 行业知识库</strong></td><td>312</td><td>12,300</td><td>text-embedding-3</td><td>✓</td>
            <td><span class="badge badge-green">可用</span></td>
            <td><span class="link-action" @click="$router.push('/document/1')">详情</span> · <span class="link-action" @click="$router.push('/debug')">检索</span></td>
          </tr>
          <tr>
            <td><strong>📋 求职策略库</strong></td><td>300</td><td>6,580</td><td>text-embedding-3</td><td>✓</td>
            <td><span class="badge badge-red">失败 2</span></td>
            <td><span class="link-action danger">重试</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.upload-zone {
  border: 2px dashed var(--border);
  border-radius: 10px;
  padding: 28px;
  text-align: center;
  margin-bottom: 18px;
  background: #fafbfc;
}
.upload-icon { font-size: 28px; margin-bottom: 6px; }
.upload-text { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.upload-hint { font-size: 10px; color: var(--text-muted); }

.pipeline { display: flex; align-items: center; gap: 0; font-size: 10px; margin-bottom: 18px; }
.pipe-item {
  flex: 1; background: var(--light); border-radius: 8px; padding: 10px 8px;
  text-align: center; border: 2px solid transparent;
}
.pipe-item.active { background: #eff6ff; border-color: var(--blue); }
.pipe-item.done { background: #f0fdf4; border-color: var(--green); }
.pipe-icon { font-size: 16px; }
.pipe-name { font-weight: 600; font-size: 11px; }
.pipe-desc { font-size: 9px; color: var(--text-muted); }
.pipe-arrow { color: var(--border); font-size: 16px; padding: 0 6px; }

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th, .data-table td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--border); }
.data-table th { background: var(--light); font-weight: 600; color: var(--slate); font-size: 11px; text-transform: uppercase; }
.data-table tbody tr:hover { background: #f8fafc; }
.data-table td { color: var(--gray); }
</style>
