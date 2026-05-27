<template>
  <div>
    <div class="page-body">
      <div class="doc-layout">
        <div class="doc-left">
          <div class="section-title">📄 原文</div>
          <div class="chunk-card yellow">
            <div class="chunk-title">Chunk #12 · 任职要求第3条</div>
            <div class="chunk-text">"熟悉分布式系统设计，有消息队列（Kafka/RabbitMQ）使用经验，了解CAP理论..."</div>
          </div>
          <div class="chunk-card blue">
            <div class="chunk-title">Chunk #13 · 任职要求第4条</div>
            <div class="chunk-text">"具备系统性能优化经验，能够定位和解决复杂性能问题..."</div>
          </div>
          <div class="chunk-card yellow">
            <div class="chunk-title">Chunk #14 · 加分项</div>
            <div class="chunk-text">"有大模型应用开发经验者优先，熟悉LangChain/LlamaIndex等框架..."</div>
          </div>
        </div>
        <div class="doc-right">
          <div class="section-title">📊 文档元信息</div>
          <div class="meta-list">
            <div class="meta-row"><span class="meta-key">文件名</span><span class="meta-val">字节跳动_后端JD.pdf</span></div>
            <div class="meta-row"><span class="meta-key">大小</span><span class="meta-val">245 KB</span></div>
            <div class="meta-row"><span class="meta-key">分块策略</span><span class="meta-val">语义分块 · 512 tokens/块 · 重叠 64</span></div>
            <div class="meta-row"><span class="meta-key">总块数</span><span class="meta-val">28</span></div>
            <div class="meta-row"><span class="meta-key">Embedding</span><span class="meta-val">text-embedding-3-large</span></div>
            <div class="meta-row"><span class="meta-key">索引时间</span><span class="meta-val">2026-05-20 14:23</span></div>
            <div class="meta-row"><span class="meta-key">状态</span><span class="meta-val"><span class="badge badge-green">已索引</span></span></div>
          </div>
          <div class="search-action" @click="$router.push('/debug')">🔍 在此文档中检索 →</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.doc-layout { display: grid; grid-template-columns: 1fr 1fr; background: #fff; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.doc-left { padding: 20px; border-right: 1px solid var(--border); }
.doc-right { padding: 20px; background: #fafbfc; }
.section-title { font-weight: 600; font-size: 14px; margin-bottom: 14px; color: var(--slate); }
.chunk-card { padding: 10px 12px; border-radius: 6px; margin-bottom: 8px; font-size: 12px; line-height: 1.6; }
.chunk-card.yellow { background: #fef3c7; }
.chunk-card.blue { background: #dbeafe; }
.chunk-title { font-weight: 600; font-size: 11px; margin-bottom: 4px; }
.chunk-text { color: var(--gray); }
.meta-list { margin-bottom: 16px; }
.meta-row { display: flex; padding: 6px 0; border-bottom: 1px solid rgba(0,0,0,0.04); font-size: 12px; }
.meta-key { color: var(--text-muted); width: 80px; flex-shrink: 0; }
.meta-val { font-weight: 500; }
.search-action { padding: 10px; border: 1px solid var(--blue); border-radius: 8px; text-align: center; color: var(--blue); font-size: 12px; cursor: pointer; font-weight: 500; transition: background 0.15s; }
.search-action:hover { background: #eff6ff; }
</style>
