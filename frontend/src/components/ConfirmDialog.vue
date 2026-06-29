<template>
  <Teleport to="body">
    <transition name="confirm-fade">
      <div v-if="state.open" class="confirm-mask" @click.self="onCancel">
        <div class="confirm-dialog" role="dialog" aria-modal="true">
          <div class="confirm-head">
            <div class="confirm-icon" :class="`is-${state.variant}`">{{ state.icon }}</div>
            <div class="confirm-text">
              <div class="confirm-title">{{ state.title }}</div>
              <div v-if="state.message" class="confirm-message">{{ state.message }}</div>
              <div v-if="state.detail" class="confirm-detail">{{ state.detail }}</div>
              <input
                v-if="state.input"
                ref="inputRef"
                v-model="state.inputValue"
                class="confirm-input"
                :placeholder="state.inputPlaceholder"
                @keydown.enter.prevent="onOk"
              />
            </div>
          </div>
          <div class="confirm-actions">
            <button class="confirm-btn-cancel" @click="onCancel">{{ state.cancelText }}</button>
            <template v-if="state.choices.length > 0">
              <button
                v-for="choice in state.choices"
                :key="String(choice.value)"
                class="confirm-btn-ok"
                :class="`is-${choice.variant || 'default'}`"
                @click="onPick(choice.value)"
              >
                {{ choice.label }}
              </button>
            </template>
            <button
              v-else
              class="confirm-btn-ok"
              :class="`is-${state.variant}`"
              :disabled="state.input && !String(state.inputValue).trim()"
              @click="onOk"
            >
              {{ state.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { confirmState as state, resolveConfirm } from '../composables/useConfirm'

const inputRef = ref(null)

function onOk() {
  if (state.input) {
    const v = String(state.inputValue || '').trim()
    if (!v) return
    resolveConfirm(v)
    return
  }
  resolveConfirm(true)
}

// 打开输入模式时自动聚焦
watch(
  () => state.open,
  (open) => {
    if (open && state.input) nextTick(() => inputRef.value?.focus())
  },
)

function onCancel() {
  resolveConfirm(state.cancelValue)
}

function onPick(value) {
  resolveConfirm(value)
}

function onKey(e) {
  if (!state.open) return
  if (e.key === 'Escape') {
    onCancel()
  } else if (e.key === 'Enter' && state.choices.length === 0) {
    // 多选模式下 Enter 不默选任何一项，避免误触"覆盖"这种破坏性操作
    onOk()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped>
.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: grid;
  place-items: center;
  background: rgba(15, 23, 42, 0.45);
  padding: 16px;
}

.confirm-dialog {
  width: min(420px, 100%);
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.25);
  overflow: hidden;
}

.confirm-head {
  display: flex;
  gap: 14px;
  padding: 22px 22px 16px;
}

.confirm-icon {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  font-size: 22px;
  background: #eff6ff;
}
.confirm-icon.is-danger { background: #fee2e2; }
.confirm-icon.is-warning { background: #fef3c7; }

.confirm-text {
  flex: 1 1 auto;
  min-width: 0;
}

.confirm-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.4;
}

.confirm-message {
  margin-top: 6px;
  color: #475569;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.confirm-detail {
  margin-top: 8px;
  padding: 8px 10px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.confirm-input {
  width: 100%;
  margin-top: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 13px;
  font-family: inherit;
  color: #0f172a;
}
.confirm-input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }

.confirm-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 22px 18px;
  background: #fafbfc;
}

.confirm-btn-cancel,
.confirm-btn-ok {
  padding: 7px 18px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s ease;
}

.confirm-btn-cancel {
  background: #fff;
  border-color: #e2e8f0;
  color: #475569;
}
.confirm-btn-cancel:hover {
  background: #f1f5f9;
}

.confirm-btn-ok {
  background: var(--primary);
  color: #fff;
}
.confirm-btn-ok:hover:not(:disabled) { background: var(--primary-hover); }
.confirm-btn-ok:disabled { opacity: 0.5; cursor: not-allowed; }

.confirm-btn-ok.is-danger {
  background: #ef4444;
}
.confirm-btn-ok.is-danger:hover { background: #dc2626; }

.confirm-btn-ok.is-warning {
  background: #f59e0b;
}
.confirm-btn-ok.is-warning:hover { background: #d97706; }

.confirm-fade-enter-active,
.confirm-fade-leave-active {
  transition: opacity 0.2s ease;
}
.confirm-fade-enter-from,
.confirm-fade-leave-to {
  opacity: 0;
}
.confirm-fade-enter-active .confirm-dialog,
.confirm-fade-leave-active .confirm-dialog {
  transition: transform 0.2s ease;
}
.confirm-fade-enter-from .confirm-dialog {
  transform: scale(0.95);
}
.confirm-fade-leave-to .confirm-dialog {
  transform: scale(0.95);
}
</style>
