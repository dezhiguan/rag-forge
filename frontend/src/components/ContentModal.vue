<template>
  <Teleport to="body">
    <transition name="cm-fade">
      <div v-if="modelValue" class="cm-overlay" @click.self="close">
        <div class="cm-dialog" role="dialog" aria-modal="true">
          <div class="cm-head">
            <div class="cm-title" :title="title">{{ title }}</div>
            <button class="cm-x" type="button" title="关闭" @click="close">✕</button>
          </div>
          <div v-if="$slots.meta" class="cm-meta"><slot name="meta" /></div>
          <div class="cm-body">
            <pre class="cm-content">{{ content }}</pre>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  content: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])

function close() {
  emit('update:modelValue', false)
}
function onKey(e) {
  if (e.key === 'Escape' && props.modelValue) close()
}
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped>
.cm-overlay {
  position: fixed; inset: 0; z-index: 70;
  background: rgba(15, 23, 42, .42);
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.cm-dialog {
  width: min(760px, 96vw); max-height: 86vh;
  background: #fff; border-radius: 14px;
  box-shadow: 0 20px 60px rgba(15, 23, 42, .28);
  display: flex; flex-direction: column; overflow: hidden;
}
.cm-head {
  display: flex; align-items: center; gap: 10px;
  padding: 16px 20px; border-bottom: 1px solid var(--border);
}
.cm-title {
  font-weight: 600; font-size: 15px; color: var(--slate);
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cm-x {
  margin-left: auto; cursor: pointer; border: none; background: none;
  font-size: 18px; color: var(--text-muted); line-height: 1;
}
.cm-x:hover { color: var(--slate); }
.cm-meta {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 12px 20px 0;
}
.cm-body { padding: 12px 20px 20px; overflow: auto; }
.cm-content {
  margin: 0; white-space: pre-wrap; word-break: break-word;
  font-size: 13.5px; line-height: 1.7; color: var(--gray, #475569);
  font-family: inherit;
}
.cm-fade-enter-active, .cm-fade-leave-active { transition: opacity .18s ease; }
.cm-fade-enter-from, .cm-fade-leave-to { opacity: 0; }
</style>
