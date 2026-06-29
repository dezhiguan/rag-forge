<template>
  <div class="noti-bell" :class="{ collapsed }">
    <button class="bell-btn" title="通知" @click.stop="toggle">
      <span class="bell-ico">🔔</span>
      <span v-show="!collapsed" class="bell-label">通知</span>
      <span v-if="pending.length" class="bell-badge">{{ pending.length }}</span>
    </button>

    <div v-if="open" class="bell-menu" @click.stop>
      <div class="menu-label">待处理邀请</div>
      <div v-if="!pending.length" class="empty">暂无新通知</div>
      <div v-for="inv in pending" :key="inv.id" class="noti-item">
        <div class="noti-title">「{{ inv.orgName }}」邀请你加入</div>
        <div class="noti-sub">角色 {{ inv.role }}</div>
        <div class="noti-actions">
          <button class="btn-accept" :disabled="busy" @click="accept(inv)">接受</button>
          <button class="btn-decline" :disabled="busy" @click="decline(inv)">拒绝</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { myInvitations, acceptInvitation, declineInvitation } from '../api/notification'

defineProps({ collapsed: { type: Boolean, default: false } })

const open = ref(false)
const busy = ref(false)
const pending = ref([])

async function load() {
  try {
    const res = await myInvitations()
    pending.value = Array.isArray(res?.data) ? res.data : []
  } catch {
    pending.value = []
  }
}

function toggle() {
  open.value = !open.value
}

async function accept(inv) {
  if (busy.value) return
  busy.value = true
  try {
    await acceptInvitation(inv.id)
    // 加入新组织后整页重载，让组织切换器/数据刷新
    window.location.reload()
  } finally {
    busy.value = false
  }
}

async function decline(inv) {
  if (busy.value) return
  busy.value = true
  try {
    await declineInvitation(inv.id)
    pending.value = pending.value.filter((i) => i.id !== inv.id)
  } finally {
    busy.value = false
  }
}

function onDocClick() {
  open.value = false
}

onMounted(() => {
  load()
  document.addEventListener('click', onDocClick)
})
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<style scoped>
.noti-bell { position: relative; padding: 0 12px 8px; }
.bell-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  background: none;
  border: none;
  padding: 8px 11px;
  border-radius: 9px;
  cursor: pointer;
  color: var(--gray);
  font-size: 13px;
  font-weight: 500;
}
.bell-btn:hover { background: rgba(0, 0, 0, 0.04); color: var(--navy); }
.bell-ico { font-size: 15px; width: 18px; text-align: center; }
.bell-label { flex: 1; text-align: left; }
.bell-badge {
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--red, #ef4444);
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.collapsed .bell-label { display: none; }
.bell-menu {
  position: absolute;
  bottom: calc(100% - 2px);
  left: 12px;
  right: 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 13px;
  box-shadow: var(--shadow-md);
  padding: 7px;
  z-index: 60;
}
.menu-label {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--text-muted);
  letter-spacing: 0.4px;
  padding: 7px 9px 5px;
}
.empty { padding: 18px 0; text-align: center; color: var(--text-muted); font-size: 12.5px; }
.noti-item { padding: 9px; border-radius: 9px; }
.noti-item:hover { background: #f6f8fb; }
.noti-title { font-size: 13px; font-weight: 600; color: var(--slate); }
.noti-sub { font-size: 11.5px; color: var(--text-muted); margin-top: 2px; }
.noti-actions { display: flex; gap: 8px; margin-top: 8px; }
.btn-accept {
  background: #fff;
  color: #15803d;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.btn-decline {
  background: #fff;
  color: var(--gray);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
}
</style>
