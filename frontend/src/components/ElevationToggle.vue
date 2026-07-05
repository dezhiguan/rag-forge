<template>
  <button
    v-if="canElevate"
    class="elev-toggle"
    :class="{ on: active }"
    :title="active
      ? '正在以超管身份查看全平台数据（已留审计）· 点击关闭提权'
      : '超管破玻璃：提权查看全平台数据（需填理由、将被审计）'"
    @click="toggle"
  >
    <span class="ei">{{ active ? '🔓' : '🛡' }}</span>
    {{ active ? '已提权 · 全平台（点击关闭）' : '提权查看全平台 · 审计' }}
  </button>
</template>

<script setup>
import { computed, onUnmounted, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useElevation } from '../composables/useElevation'
import { useOrg } from '../composables/useOrg'
import { confirm as confirmDialog } from '../composables/useConfirm'

// 每个需要跨组织的页面在页头右上放一个本组件。开启需弹窗必填理由；关闭一键；离开页面自动关闭。
const props = defineProps({ pageName: { type: String, default: '本页' } })
const emit = defineEmits(['change'])

const { ragRole } = useAuth()
const { isSystem } = useOrg()
const isAdmin = computed(() => ragRole.value === 'ADMIN')
// 提权（跨组织破玻璃）是系统组织的治理能力：仅超管、且当前处于系统组织时可用。
// 超管的个人组织是私人空间，与普通用户一致，不得提权。
const canElevate = computed(() => isAdmin.value && isSystem.value)
const { active, activate, deactivate } = useElevation()

async function toggle() {
  if (active.value) {
    deactivate()
    emit('change', false)
    return
  }
  const reason = await confirmDialog({
    title: '提权查看全平台（超管破玻璃）',
    message: `你将临时以超管身份跨组织访问「${props.pageName}」的全平台数据，本次访问全程审计。请填写访问理由：`,
    input: true,
    inputPlaceholder: '如：排查某组织质量下降 / 客户支持工单 #123 / 安全事件核查',
    confirmText: '确认进入',
    variant: 'danger',
  })
  // 必填理由：取消或留空则不提权（保持当前组织口径）。
  if (!reason || !String(reason).trim()) {
    return
  }
  if (activate(String(reason).trim())) {
    emit('change', true)
  }
}

// 一旦离开系统组织（如切回个人组织），立即结束提权：个人组织不得携带破玻璃口径。
watch(canElevate, (ok) => {
  if (!ok && active.value) {
    deactivate()
    emit('change', false)
  }
})

// 离开页面即结束提权：不跨页粘住。
onUnmounted(() => {
  if (active.value) {
    deactivate()
  }
})
</script>

<style scoped>
.elev-toggle {
  border: 1px solid #cfe0ff;
  background: #eef4ff;
  color: #2f6bff;
  font-weight: 700;
  font-size: 12.5px;
  padding: 7px 13px;
  border-radius: 9px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  transition: filter 0.15s;
}
.elev-toggle:hover {
  filter: brightness(0.98);
}
.elev-toggle.on {
  background: #fff2e0;
  border-color: #f0cf9a;
  color: #a35a00;
}
.elev-toggle .ei {
  font-size: 14px;
}
</style>
