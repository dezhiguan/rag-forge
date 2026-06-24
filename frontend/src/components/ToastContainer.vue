<template>
  <Teleport to="body">
    <transition-group tag="div" name="toast" class="toast-stack" aria-live="polite">
      <div
        v-for="t in toastState.toasts"
        :key="t.id"
        class="toast"
        :class="`toast-${t.type}`"
        role="status"
        @click="dismiss(t.id)"
      >
        <span class="toast-icon">{{ iconFor(t.type) }}</span>
        <div class="toast-body">
          <div v-if="t.title" class="toast-title">{{ t.title }}</div>
          <div class="toast-message">{{ t.message }}</div>
        </div>
        <button class="toast-close" :aria-label="`关闭`" @click.stop="dismiss(t.id)">×</button>
      </div>
    </transition-group>
  </Teleport>
</template>

<script setup>
import { toastState, dismiss } from '../composables/useToast'

const iconMap = {
  success: '✓',
  error: '✕',
  warning: '⚠',
  info: 'ⓘ',
}

function iconFor(type) {
  return iconMap[type] || 'ⓘ'
}
</script>

<style scoped>
.toast-stack {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 280px;
  max-width: 420px;
  padding: 12px 14px;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  border-left-width: 4px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  font-size: 13px;
  pointer-events: auto;
  cursor: pointer;
}

.toast-success { border-left-color: #10b981; }
.toast-error { border-left-color: #ef4444; }
.toast-warning { border-left-color: #f59e0b; }
.toast-info { border-left-color: #3b82f6; }

.toast-icon {
  flex: 0 0 auto;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  margin-top: 1px;
}
.toast-success .toast-icon { background: #10b981; }
.toast-error .toast-icon { background: #ef4444; }
.toast-warning .toast-icon { background: #f59e0b; }
.toast-info .toast-icon { background: #3b82f6; }

.toast-body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.toast-title {
  font-weight: 600;
  color: #1e293b;
  font-size: 13px;
}

.toast-message {
  color: #475569;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.toast-close {
  flex: 0 0 auto;
  background: transparent;
  border: none;
  color: #94a3b8;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
}
.toast-close:hover { color: #475569; }

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateX(20px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

@media (max-width: 768px) {
  .toast-stack {
    top: 12px;
    left: 12px;
    right: 12px;
  }
  .toast {
    min-width: 0;
    max-width: none;
  }
}
</style>
