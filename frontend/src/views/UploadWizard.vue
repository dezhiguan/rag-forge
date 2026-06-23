<template>
  <div class="page-body">
    <div class="wizard-shell">
      <section class="wizard-main">
        <div class="eyebrow">Upload Wizard</div>
        <h1>多模态上传向导</h1>
        <p class="lead">选择文件后按 MIME 类型预判处理管道，图片会进入 OCR、图像描述和 image_vector 索引流程。</p>

        <label class="drop-zone">
          <input type="file" @change="onFileChange">
          <span class="drop-icon">⬆</span>
          <span class="drop-title">{{ fileName || '选择一个文档或图片' }}</span>
          <span class="drop-sub">{{ fileMeta || '文档：PDF / Word / Markdown / HTML，图片：PNG / JPG / GIF / WEBP（自动 OCR）' }}</span>
        </label>
      </section>

      <aside class="route-panel">
        <div class="route-label">识别结果</div>
        <div class="route-card" :class="routeClass">
          <div class="route-name">{{ routeName }}</div>
          <div class="route-desc">{{ routeDesc }}</div>
        </div>
        <div class="hint-grid">
          <div>
            <strong>text / PDF</strong>
            <span>Parser → Cleaner → Chunker → text_vector</span>
          </div>
          <div>
            <strong>image</strong>
            <span>OCR → Vision Caption → image_vector</span>
          </div>
          <div>
            <strong>audio / video</strong>
            <span>二期处理，当前不接收</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const file = ref(null)

const fileName = computed(() => file.value?.name || '')
const fileMeta = computed(() => {
  if (!file.value) return ''
  return `${file.value.type || 'unknown'} · ${(file.value.size / 1024 / 1024).toFixed(2)} MB`
})
const routeName = computed(() => {
  const type = file.value?.type || ''
  if (!file.value) return '等待选择文件'
  if (type.startsWith('image/')) return 'ImagePipeline'
  if (type.startsWith('text/') || type === 'application/pdf' || type.includes('wordprocessingml') || type === 'application/msword') return 'TextPipeline'
  if (type.startsWith('audio/') || type.startsWith('video/')) return '暂不支持'
  return '需要人工确认'
})
const routeDesc = computed(() => {
  if (!file.value) return '系统会根据浏览器提供的 contentType 给出处理路径。'
  if (routeName.value === 'ImagePipeline') return '将提取 OCR 文本、生成图像描述，并写入 image_vector。'
  if (routeName.value === 'TextPipeline') return '沿用文本解析、清洗、分块和 text_vector 索引。'
  if (routeName.value === '暂不支持') return '音视频推迟到多模态二期。'
  return '建议补齐 contentType 后再上传。'
})
const routeClass = computed(() => {
  if (routeName.value === 'ImagePipeline') return 'image'
  if (routeName.value === 'TextPipeline') return 'text'
  if (routeName.value === '暂不支持') return 'blocked'
  return ''
})

function onFileChange(event) {
  file.value = event.target.files?.[0] || null
}
</script>

<style scoped>
.wizard-shell {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(280px, 0.65fr);
  gap: 18px;
}

.wizard-main,
.route-panel {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: #fff;
  padding: 22px;
}

.eyebrow {
  color: var(--blue);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1 {
  color: var(--slate);
  font-size: 26px;
  margin: 8px 0;
}

.lead {
  color: var(--text-muted);
  line-height: 1.7;
  margin: 0 0 18px;
}

.drop-zone {
  align-items: center;
  border: 1px dashed #94a3b8;
  border-radius: var(--radius-md);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  justify-content: center;
  min-height: 220px;
  background: #f8fafc;
}

.drop-zone input {
  display: none;
}

.drop-icon {
  background: #0f172a;
  border-radius: 999px;
  color: #fff;
  display: grid;
  font-size: 22px;
  height: 48px;
  place-items: center;
  width: 48px;
}

.drop-title {
  color: var(--slate);
  font-size: 16px;
  font-weight: 800;
}

.drop-sub {
  color: var(--text-muted);
  font-size: 13px;
}

.route-label {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 10px;
}

.route-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 14px;
  background: #f8fafc;
}

.route-card.image {
  border-color: #f59e0b;
  background: #fffbeb;
}

.route-card.text {
  border-color: #38bdf8;
  background: #f0f9ff;
}

.route-card.blocked {
  border-color: #f87171;
  background: #fef2f2;
}

.route-name {
  color: var(--slate);
  font-size: 18px;
  font-weight: 900;
  margin-bottom: 6px;
}

.route-desc {
  color: var(--text-muted);
  line-height: 1.6;
}

.hint-grid {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.hint-grid div {
  border-top: 1px solid var(--border);
  padding-top: 10px;
}

.hint-grid strong,
.hint-grid span {
  display: block;
}

.hint-grid span {
  color: var(--text-muted);
  font-size: 12px;
  margin-top: 4px;
}

@media (max-width: 860px) {
  .wizard-shell {
    grid-template-columns: 1fr;
  }
}
</style>
