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
          <span class="drop-sub">{{ fileMeta || '文档：PDF / Word / Markdown / HTML / TXT，图片：PNG / JPG / GIF / WEBP（自动 OCR）' }}</span>
        </label>

        <div class="upload-controls">
          <label class="field">
            <span>上传到知识库</span>
            <select v-model="uploadKbId">
              <option value="" disabled>请选择知识库</option>
              <option v-for="kb in kbList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
            </select>
          </label>
          <button class="upload-button" :disabled="!file || !uploadKbId || uploading" @click="startUpload()">
            {{ uploading ? '上传中...' : '开始上传' }}
          </button>
        </div>

        <div v-if="file || uploadError" class="upload-status" :class="{ failed: uploadError, done: uploadPhase === 'done' }">
          <div class="upload-status-head">
            <strong>{{ uploadError ? uploadError : phaseLabel }}</strong>
            <span>{{ uploadProgress }}%</span>
          </div>
          <div class="progress-bar">
            <span :style="{ width: `${uploadProgress}%` }" />
          </div>
          <button v-if="uploadError" class="retry-button" @click="startUpload()">重试</button>
        </div>
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
import { computed, onMounted, ref } from 'vue'
import { listKb } from '../api/kb'
import { uploadDocument } from '../api/document'
import { uploadErrorMessage } from '../api/upload'

const file = ref(null)
const kbList = ref([])
const uploadKbId = ref('')
const uploading = ref(false)
const uploadPhase = ref('queued')
const uploadProgress = ref(0)
const uploadError = ref('')

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
const phaseLabel = computed(() => {
  const map = {
    queued: '等待上传',
    hashing: '计算文件指纹',
    presigning: '申请 OSS 直传地址',
    uploading: '上传 OSS',
    relay: '服务端上传',
    registering: '登记文档',
    done: '上传完成',
  }
  return map[uploadPhase.value] || uploadPhase.value
})

function onFileChange(event) {
  file.value = event.target.files?.[0] || null
  uploadPhase.value = 'queued'
  uploadProgress.value = 0
  uploadError.value = ''
}

async function startUpload(onConflict = 'REJECT') {
  if (!file.value || !uploadKbId.value) return
  uploading.value = true
  uploadError.value = ''
  uploadProgress.value = 0
  try {
    await uploadDocument(uploadKbId.value, file.value, {
      onConflict,
      onProgress: (progress) => {
        uploadProgress.value = Math.max(uploadProgress.value, progress)
      },
      onPhaseChange: (phase) => {
        uploadPhase.value = phase
        uploadProgress.value = Math.max(uploadProgress.value, phaseProgress(phase))
      },
    })
    uploadPhase.value = 'done'
    uploadProgress.value = 100
  } catch (e) {
    uploadError.value = uploadErrorMessage(e)
  } finally {
    uploading.value = false
  }
}

function phaseProgress(phase) {
  const map = {
    queued: 0,
    hashing: 2,
    presigning: 8,
    uploading: 10,
    relay: 15,
    registering: 96,
    done: 100,
  }
  return map[phase] ?? 0
}

onMounted(async () => {
  const res = await listKb()
  kbList.value = res.data ?? []
  uploadKbId.value = kbList.value[0]?.id || ''
})
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
  color: var(--primary);
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

.upload-controls {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 140px;
  gap: 12px;
  align-items: end;
  margin-top: 16px;
}

.field span {
  display: block;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 6px;
}

.field select {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  background: #fff;
  color: var(--slate);
}

.upload-button,
.retry-button {
  border: none;
  border-radius: var(--radius-sm);
  background: var(--primary);
  color: #fff;
  cursor: pointer;
  font-weight: 800;
  min-height: 36px;
  padding: 8px 12px;
}

.upload-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.upload-status {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #f8fafc;
  margin-top: 14px;
  padding: 12px;
}

.upload-status.done {
  border-color: rgba(22, 163, 74, 0.28);
  background: #f0fdf4;
}

.upload-status.failed {
  border-color: rgba(220, 38, 38, 0.28);
  background: #fef2f2;
}

.upload-status-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--slate);
  font-size: 13px;
}

.progress-bar {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e2e8f0;
  margin-top: 8px;
}

.progress-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--primary);
  transition: width 0.16s ease;
}

.retry-button {
  margin-top: 10px;
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
