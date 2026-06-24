<template>
  <Teleport to="body">
    <transition name="rechunk-fade">
      <div v-if="modelValue" class="rechunk-mask" @click.self="close">
        <div class="rechunk-dialog" role="dialog" aria-modal="true">
          <header class="rechunk-header">
            <div>
              <div class="rechunk-title">📊 重新分块</div>
              <div class="rechunk-filename">{{ doc?.filename || '—' }}</div>
            </div>
            <button type="button" class="rechunk-close" aria-label="关闭" @click="close">✕</button>
          </header>

          <section v-if="documentType === 'image'" class="rechunk-body image-only">
            <div class="image-icon">📷</div>
            <p class="image-lead">该文档由图像管道处理（OCR + 图像向量）</p>
            <p class="image-desc">重新处理会重新生成 OCR 文本和图像向量。期间该文档不可检索。</p>
          </section>

          <section v-else class="rechunk-body">
            <div v-if="documentType === 'mixed'" class="hint-banner">
              💡 该文档含 {{ imageChunkCount }} 张图片，图片部分仍由图像管道处理。<br>
              下方策略仅作用于文本部分。
            </div>

            <div class="section-label">分块策略</div>
            <div class="strategy-list">
              <label
                v-for="item in strategyOptions"
                :key="item.value"
                class="strategy-item"
                :class="{ disabled: item.disabled }"
                :title="item.disabled ? item.disabledReason : ''"
              >
                <input
                  v-model="selectedStrategy"
                  type="radio"
                  :value="item.value"
                  :disabled="item.disabled"
                >
                <div class="strategy-copy">
                  <div class="strategy-name">
                    {{ item.label }}
                    <span v-if="item.recommended" class="strategy-tag">推荐</span>
                    <span v-if="item.warning" class="strategy-warning">{{ item.warning }}</span>
                  </div>
                  <div class="strategy-desc">{{ item.description }}</div>
                </div>
              </label>
            </div>

            <div v-if="showFixedParams" class="params-row">
              <label class="param-field">
                块大小（字符）：
                <input v-model.number="chunkSize" type="number" min="64" max="2048">
              </label>
              <label class="param-field">
                块重叠（字符）：
                <input v-model.number="chunkOverlap" type="number" min="0" max="512">
              </label>
            </div>

            <div class="strategy-compare">
              当前策略：<strong>{{ currentStrategyLabel }}</strong>，新策略：<strong>{{ newStrategyLabel }}</strong>
            </div>

            <div class="warning-banner">
              ⚠️ 重新分块会删除旧分块并重新生成向量索引，期间该文档不可检索。
            </div>
          </section>

          <footer class="rechunk-footer">
            <button type="button" class="btn-cancel" @click="close">取消</button>
            <button
              type="button"
              class="btn-submit warning"
              :disabled="submitDisabled"
              @click="submit"
            >
              {{ documentType === 'image' ? '重新处理图像' : '重新分块' }}
            </button>
          </footer>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  doc: { type: Object, default: null },
  chunks: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue', 'submit'])

const CHUNKER_STRATEGY_LABELS = {
  MARKDOWN_HEADING: '按标题分块',
  FIXED_WINDOW: '固定窗口',
  RECURSIVE: '递归切分',
  SEMANTIC: '语义分块',
  TABLE_AWARE: '表格感知',
  IMAGE_PIPELINE: '图像管道',
}

const STRATEGIES_USING_FIXED_PARAMS = new Set(['FIXED_WINDOW', 'RECURSIVE'])
const SEMANTIC_MIN_TEXT = 2000

const selectedStrategy = ref('MARKDOWN_HEADING')
const chunkSize = ref(512)
const chunkOverlap = ref(64)

const modalities = computed(() =>
  (props.chunks || []).map((c) => (c.chunkModality || 'TEXT').toUpperCase()),
)

const imageChunkCount = computed(() =>
  modalities.value.filter((m) => m.startsWith('IMAGE')).length,
)

const textChunkCount = computed(() =>
  modalities.value.filter((m) => !m.startsWith('IMAGE')).length,
)

const documentType = computed(() => {
  if (!props.chunks?.length) {
    return (props.doc?.fileType || '').toLowerCase().startsWith('image/') ? 'image' : 'text'
  }
  if (imageChunkCount.value > 0 && textChunkCount.value === 0) return 'image'
  if (imageChunkCount.value > 0 && textChunkCount.value > 0) return 'mixed'
  return 'text'
})

const textLength = computed(() => {
  const report = parseCleanReport(props.doc?.cleanReportJson)
  if (report?.cleanedLength != null) return Number(report.cleanedLength) || 0
  return 0
})

const semanticDisabled = computed(() => textLength.value < SEMANTIC_MIN_TEXT)

const currentDominantStrategy = computed(() => {
  const list = props.chunks || []
  if (!list.length) return 'MARKDOWN_HEADING'
  if (documentType.value === 'image') return 'IMAGE_PIPELINE'
  const counts = new Map()
  for (const chunk of list) {
    if ((chunk.chunkModality || '').toUpperCase().startsWith('IMAGE')) continue
    const key = chunk.chunkerStrategy || 'MARKDOWN_HEADING'
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1])
  return sorted[0]?.[0] || 'MARKDOWN_HEADING'
})

const strategyOptions = computed(() => [
  {
    value: 'MARKDOWN_HEADING',
    label: '按标题分块',
    recommended: true,
    description: '按 H1/H2/H3 标题层级切分，适合 Markdown / 结构化文档',
    disabled: false,
  },
  {
    value: 'FIXED_WINDOW',
    label: '固定窗口',
    description: '按固定字符数切分，适合纯文本 / 无结构化文档',
    disabled: false,
  },
  {
    value: 'RECURSIVE',
    label: '递归切分',
    description: '按段落→句子→字符智能分层，本地兜底（不调模型）',
    disabled: false,
  },
  {
    value: 'SEMANTIC',
    label: '语义分块',
    warning: '需 ≥2000 字 + 调用 embedding API（慢、有成本）',
    description: '按内容主题切分，质量最高但耗时和成本最大',
    disabled: semanticDisabled.value,
    disabledReason: '文本太短，不支持语义分块',
  },
  {
    value: 'TABLE_AWARE',
    label: '表格感知',
    description: '保护表格完整性，适合含表格的文档',
    disabled: false,
  },
])

const showFixedParams = computed(() => STRATEGIES_USING_FIXED_PARAMS.has(selectedStrategy.value))

const currentStrategyLabel = computed(() =>
  CHUNKER_STRATEGY_LABELS[currentDominantStrategy.value] || currentDominantStrategy.value,
)

const newStrategyLabel = computed(() =>
  CHUNKER_STRATEGY_LABELS[selectedStrategy.value] || selectedStrategy.value,
)

const submitDisabled = computed(() => {
  if (documentType.value === 'image') return false
  if (selectedStrategy.value === 'SEMANTIC' && semanticDisabled.value) return true
  if (showFixedParams.value) {
    if (!Number.isFinite(chunkSize.value) || chunkSize.value < 64 || chunkSize.value > 2048) return true
    if (!Number.isFinite(chunkOverlap.value) || chunkOverlap.value < 0 || chunkOverlap.value > 512) return true
  }
  return false
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (documentType.value === 'image') return
    selectedStrategy.value = currentDominantStrategy.value === 'IMAGE_PIPELINE'
      ? 'MARKDOWN_HEADING'
      : currentDominantStrategy.value
    chunkSize.value = 512
    chunkOverlap.value = 64
  },
)

function parseCleanReport(raw) {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function close() {
  emit('update:modelValue', false)
}

function onKey(e) {
  if (!props.modelValue) return
  if (e.key === 'Escape') close()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function submit() {
  if (submitDisabled.value) return
  if (documentType.value === 'image') {
    emit('submit', { imageOnly: true })
    return
  }
  const payload = { strategy: selectedStrategy.value }
  if (showFixedParams.value) {
    payload.chunkSize = chunkSize.value
    payload.chunkOverlap = chunkOverlap.value
  }
  emit('submit', payload)
}
</script>

<style scoped>
.rechunk-mask {
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: grid;
  place-items: center;
  background: rgba(15, 23, 42, 0.45);
  padding: 16px;
}

.rechunk-dialog {
  width: min(560px, 100%);
  max-height: min(90vh, 760px);
  overflow: auto;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.25);
}

.rechunk-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 22px 12px;
  border-bottom: 1px solid #e2e8f0;
}

.rechunk-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.rechunk-filename {
  margin-top: 4px;
  font-size: 13px;
  color: #64748b;
}

.rechunk-close {
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 18px;
  cursor: pointer;
}

.rechunk-body {
  padding: 16px 22px;
}

.image-only {
  text-align: center;
  padding-top: 28px;
  padding-bottom: 28px;
}

.image-icon {
  font-size: 42px;
  margin-bottom: 12px;
}

.image-lead {
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}

.image-desc {
  margin-top: 8px;
  color: #475569;
  line-height: 1.6;
}

.hint-banner,
.warning-banner {
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.hint-banner {
  background: #eff6ff;
  color: #1d4ed8;
  margin-bottom: 16px;
}

.warning-banner {
  margin-top: 16px;
  background: #fff7ed;
  color: #9a3412;
}

.section-label {
  font-size: 13px;
  font-weight: 700;
  color: #334155;
  margin-bottom: 10px;
}

.strategy-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.strategy-item {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
}

.strategy-item.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.strategy-copy {
  flex: 1;
}

.strategy-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.strategy-tag {
  margin-left: 6px;
  font-size: 11px;
  color: #2563eb;
}

.strategy-warning {
  margin-left: 6px;
  font-size: 11px;
  color: #d97706;
}

.strategy-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}

.params-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 16px;
}

.param-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: #475569;
}

.param-field input {
  padding: 8px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
}

.strategy-compare {
  margin-top: 16px;
  font-size: 13px;
  color: #475569;
}

.rechunk-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 22px 18px;
  border-top: 1px solid #e2e8f0;
  background: #fafbfc;
}

.btn-cancel,
.btn-submit {
  padding: 8px 18px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
}

.btn-cancel {
  background: #fff;
  border-color: #e2e8f0;
  color: #475569;
}

.btn-submit.warning {
  background: #f59e0b;
  color: #fff;
}

.btn-submit.warning:hover:not(:disabled) {
  background: #d97706;
}

.btn-submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.rechunk-fade-enter-active,
.rechunk-fade-leave-active {
  transition: opacity 0.2s ease;
}
.rechunk-fade-enter-from,
.rechunk-fade-leave-to {
  opacity: 0;
}
</style>
