<template>
  <div
    class="doc-status-cell"
    :title="normalizedStatus === 'failed' ? errorMsg || '处理失败' : undefined"
  >
    <span v-if="isProcessing(parseStatus)" class="status-icon spin">⟳</span>
    <span v-else-if="normalizedStatus === 'completed'" class="status-icon ok">✓</span>
    <span v-else-if="normalizedStatus === 'failed'" class="status-icon fail">✗</span>
    <span class="badge" :class="docStatusClass(parseStatus)">
      {{ docStatusLabel(parseStatus) }}
    </span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { docStatusClass, docStatusLabel, isProcessing, normalizeDocStatus } from '../composables/useDocumentStatus'

const props = defineProps({
  parseStatus: { type: String, default: '' },
  errorMsg: { type: String, default: '' },
})

const normalizedStatus = computed(() => normalizeDocStatus(props.parseStatus))
</script>

<style scoped>
.doc-status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  white-space: nowrap;
}

.status-icon.ok {
  color: #16a34a;
  font-weight: 700;
}

.status-icon.fail {
  color: #dc2626;
  font-weight: 700;
}

.status-icon.spin {
  color: #d97706;
  display: inline-block;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 700;
  border: 1px solid transparent;
}

.badge-green { background: #dcfce7; color: #166534; border-color: rgba(22, 101, 52, 0.2); }
.badge-amber { background: #fef3c7; color: #92400e; border-color: rgba(146, 64, 14, 0.2); }
.badge-gray { background: #f1f5f9; color: #64748b; border-color: rgba(148, 163, 184, 0.35); }
.badge-red { background: #fee2e2; color: #991b1b; border-color: rgba(239, 68, 68, 0.25); }
</style>
