<template>
  <div>
    <div class="page-body">
      <div class="doc-layout">
        <div class="doc-left">
          <div class="section-title">📄 原文 / Chunks</div>
          <div v-if="loading" class="empty-hint">加载中…</div>
          <div v-else-if="!doc || !(doc.chunks?.length > 0)" class="empty-hint">
            暂无 chunks（当前可能仍在处理中）
          </div>

          <div v-else>
            <div v-for="c in doc.chunks" :key="c.chunkIndex" class="chunk-card">
              <div class="chunk-title">Chunk #{{ c.chunkIndex }}</div>
              <div class="chunk-text">{{ c.content }}</div>
            </div>
          </div>
        </div>

        <div class="doc-right">
          <div class="section-title">📊 文档元信息</div>
          <div v-if="doc" class="meta-list">
            <div class="meta-row">
              <span class="meta-key">文件名</span>
              <span class="meta-val">{{ doc.filename }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-key">大小</span>
              <span class="meta-val">{{ formatBytes(doc.fileSize) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-key">类型</span>
              <span class="meta-val">{{ doc.fileType }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-key">总块数</span>
              <span class="meta-val">{{ doc.chunkCount ?? 0 }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-key">上传时间</span>
              <span class="meta-val">{{ formatTime(doc.createdAt) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-key">状态</span>
              <span class="meta-val">
                <span class="badge" :class="statusClass(doc.parseStatus)">
                  {{ doc.parseStatus }}
                </span>
              </span>
            </div>
          </div>

          <div class="search-action" @click="$router.push('/debug')">🔍 在此文档中检索 →</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getDocument } from '../api/document'

const route = useRoute()
const loading = ref(false)
const doc = ref(null)

async function load() {
  loading.value = true
  try {
    const id = route.params.id
    const res = await getDocument(id)
    doc.value = res.data ?? null
  } finally {
    loading.value = false
  }
}

function formatTime(iso) {
  if (!iso) return '-'
  return iso.toString().replace('T', ' ').slice(0, 19)
}

function formatBytes(bytes) {
  if (bytes == null) return '-'
  const n = Number(bytes)
  if (Number.isNaN(n)) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function statusClass(parseStatus) {
  if (parseStatus === 'pending') return 'badge-gray'
  if (parseStatus === 'success') return 'badge-green'
  if (parseStatus === 'failed') return 'badge-red'
  return 'badge-gray'
}

onMounted(load)
</script>

<style scoped>
.doc-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.doc-left {
  padding: 20px;
  border-right: 1px solid var(--border);
}
.doc-right {
  padding: 20px;
  background: #fafbfc;
}
.section-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 14px;
  color: var(--slate);
}
.chunk-card {
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.6;
  border: 1px solid var(--border);
  background: #fff;
}
.chunk-title {
  font-weight: 600;
  font-size: 11px;
  margin-bottom: 4px;
}
.chunk-text {
  color: var(--gray);
  white-space: pre-wrap;
  word-break: break-word;
}
.meta-list {
  margin-bottom: 16px;
}
.meta-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
  font-size: 12px;
}
.meta-key {
  color: var(--text-muted);
  width: 80px;
  flex-shrink: 0;
}
.meta-val {
  font-weight: 500;
}
.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid transparent;
}
.badge-green {
  background: #dcfce7;
  color: #166534;
  border-color: rgba(22, 101, 52, 0.2);
}
.badge-gray {
  background: rgba(148, 163, 184, 0.18);
  color: #64748b;
  border-color: rgba(148, 163, 184, 0.25);
}
.badge-red {
  background: rgba(239, 68, 68, 0.18);
  color: #fecaca;
  border-color: rgba(239, 68, 68, 0.25);
}
.search-action {
  padding: 10px;
  border: 1px solid var(--blue);
  border-radius: 8px;
  text-align: center;
  color: var(--blue);
  font-size: 12px;
  cursor: pointer;
  font-weight: 500;
  transition: background 0.15s;
}
.search-action:hover {
  background: #eff6ff;
}
.empty-hint {
  color: var(--text-muted);
  padding: 24px 0;
  text-align: center;
}
</style>
