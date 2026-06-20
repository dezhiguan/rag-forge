<template>
  <span class="status-badge" :class="badgeClass" :title="title">
    <span v-if="pulsing" class="status-dot" />
    {{ label }}
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: {
    type: String,
    default: '',
  },
  error: {
    type: String,
    default: '',
  },
})

const normalized = computed(() => String(props.status || '').trim().toUpperCase())

const label = computed(() => {
  const map = {
    PENDING: '排队中',
    PROCESSING: '处理中',
    REPROCESSING: '重新处理中',
    COMPLETED: '已完成',
    FAILED: '失败',
  }
  return map[normalized.value] || props.status || '-'
})

const badgeClass = computed(() => {
  const map = {
    PENDING: 'is-pending',
    PROCESSING: 'is-processing',
    REPROCESSING: 'is-reprocessing',
    COMPLETED: 'is-completed',
    FAILED: 'is-failed',
  }
  return map[normalized.value] || 'is-pending'
})

const pulsing = computed(() => normalized.value === 'PROCESSING' || normalized.value === 'REPROCESSING')
const title = computed(() => (normalized.value === 'FAILED' ? props.error || '处理失败' : undefined))
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 24px;
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: currentColor;
  animation: pulse 1.2s ease-in-out infinite;
}

.is-pending {
  background: rgba(148, 163, 184, 0.16);
  color: #64748b;
  border-color: rgba(148, 163, 184, 0.3);
}

.is-processing {
  background: #dbeafe;
  color: #1d4ed8;
  border-color: rgba(29, 78, 216, 0.2);
}

.is-reprocessing {
  background: #ffedd5;
  color: #c2410c;
  border-color: rgba(194, 65, 12, 0.22);
}

.is-completed {
  background: #dcfce7;
  color: #166534;
  border-color: rgba(22, 101, 52, 0.2);
}

.is-failed {
  background: #fee2e2;
  color: #991b1b;
  border-color: rgba(153, 27, 27, 0.22);
}

@keyframes pulse {
  0%, 100% {
    opacity: 0.45;
    transform: scale(0.86);
  }
  50% {
    opacity: 1;
    transform: scale(1.12);
  }
}
</style>
