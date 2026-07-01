<template>
  <div class="noti-bell" :class="{ collapsed }">
    <button class="bell-btn" title="通知" @click.stop="toggle">
      <span class="bell-ico">🔔</span>
      <span v-show="!collapsed" class="bell-label">通知</span>
      <span v-if="totalCount" class="bell-badge">{{ badgeText }}</span>
    </button>

    <div v-if="open" class="bell-menu" @click.stop>
      <!-- 分区一：待处理邀请（需要我操作） -->
      <div class="menu-label">待处理邀请</div>
      <div v-if="!pending.length" class="empty">暂无待处理邀请</div>
      <div v-for="inv in pending" :key="'inv-' + inv.id" class="noti-item">
        <div class="noti-title">「{{ inv.orgName }}」邀请你加入</div>
        <div class="noti-sub">角色 {{ inv.role }}</div>
        <div class="noti-actions">
          <button class="btn-accept" :disabled="busy" @click="accept(inv)">接受</button>
          <button class="btn-decline" :disabled="busy" @click="decline(inv)">拒绝</button>
        </div>
      </div>

      <!-- 分区二：通知（只读，回执等） -->
      <div class="menu-head">
        <span class="menu-label">通知</span>
        <button
          v-if="unreadNotifications.length"
          class="btn-readall"
          :disabled="busy"
          @click="readAll"
        >
          全部已读
        </button>
      </div>
      <div v-if="!notifications.length" class="empty">暂无通知</div>
      <div
        v-for="n in notifications"
        :key="'n-' + n.id"
        class="noti-item noti-msg"
        :class="{ unread: !n.readAt }"
        @click="readOne(n)"
      >
        <div class="noti-title">{{ n.title }}</div>
        <div class="noti-sub">{{ n.body }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import {
  myInvitations,
  acceptInvitation,
  declineInvitation,
  listNotifications,
  markRead,
  markAllRead,
} from '../api/notification'
import { useAuth } from '../composables/useAuth'

defineProps({ collapsed: { type: Boolean, default: false } })

const { isAuthenticated, state } = useAuth()

const open = ref(false)
const busy = ref(false)
const pending = ref([])
const notifications = ref([])
let es = null

// ORG_INVITE 通知与「待处理邀请」是同一件事，归入邀请区，避免统一未读数重复计数。
const unreadNotifications = computed(() => notifications.value.filter((n) => !n.readAt))
const totalCount = computed(() => pending.value.length + unreadNotifications.value.length)
const badgeText = computed(() => (totalCount.value > 99 ? '99+' : String(totalCount.value)))

async function load() {
  try {
    const [invRes, notiRes] = await Promise.all([
      myInvitations(),
      listNotifications(false),
    ])
    pending.value = Array.isArray(invRes?.data) ? invRes.data : []
    const list = Array.isArray(notiRes?.data) ? notiRes.data : []
    notifications.value = list.filter((n) => n.type !== 'ORG_INVITE')
  } catch {
    pending.value = []
    notifications.value = []
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
    pending.value = pending.value.filter((i) => i.id !== inv.id)
    await load()
    // 加入新组织后无整页刷新；广播事件让组织切换器/数据自行更新。
    window.dispatchEvent(new CustomEvent('ragforge:membership-changed', { detail: { orgId: inv.orgId } }))
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

async function readOne(n) {
  if (n.readAt) return
  try {
    await markRead(n.id)
    n.readAt = new Date().toISOString()
  } catch {
    /* 忽略：未读数最终以下一次拉取为准 */
  }
}

async function readAll() {
  if (busy.value) return
  busy.value = true
  try {
    await markAllRead()
    const now = new Date().toISOString()
    notifications.value.forEach((n) => {
      if (!n.readAt) n.readAt = now
    })
  } finally {
    busy.value = false
  }
}

// SSE 实时触达：收到未读变更事件即重新对齐；EventSource 原生断线重连，重连(onopen)也拉一次对齐。
function openStream() {
  closeStream()
  const token = state.accessToken
  if (!token) return
  try {
    es = new EventSource(`/api/v1/notifications/stream?token=${encodeURIComponent(token)}`)
    es.addEventListener('unread', load)
    es.onopen = load
  } catch {
    es = null
  }
}

function closeStream() {
  if (es) {
    es.close()
    es = null
  }
}

function onDocClick() {
  open.value = false
}

// 等鉴权就绪再拉取并建连，避免刷新时早于会话恢复触发 401。
watch(
  isAuthenticated,
  (ok) => {
    if (ok) {
      load()
      openStream()
    } else {
      closeStream()
      pending.value = []
      notifications.value = []
    }
  },
  { immediate: true },
)
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  closeStream()
})
</script>

<style scoped>
.noti-bell { position: relative; }
.bell-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--border);
  padding: 7px 10px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--gray);
  font-size: 13px;
  font-weight: 500;
}
.bell-btn:hover { background: var(--light); color: var(--navy); border-color: #cbd5e1; }
.bell-ico { font-size: 15px; width: 18px; text-align: center; }
.bell-label { flex: 1; text-align: left; }
.bell-badge {
  position: absolute;
  top: -6px;
  right: -6px;
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
  top: calc(100% + 8px);
  right: 0;
  left: auto;
  width: 280px;
  max-height: 420px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 13px;
  box-shadow: var(--shadow-md);
  padding: 7px;
  z-index: 60;
}
.menu-head { display: flex; align-items: center; justify-content: space-between; }
.menu-label {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--text-muted);
  letter-spacing: 0.4px;
  padding: 7px 9px 5px;
}
.btn-readall {
  background: none;
  border: none;
  color: var(--blue, #2563eb);
  font-size: 11px;
  cursor: pointer;
  padding: 7px 9px 5px;
}
.btn-readall:disabled { opacity: 0.5; cursor: default; }
.empty { padding: 18px 0; text-align: center; color: var(--text-muted); font-size: 12.5px; }
.noti-item { padding: 9px; border-radius: 9px; }
.noti-item:hover { background: #f6f8fb; }
.noti-msg { cursor: pointer; }
.noti-msg.unread { background: #f0f6ff; }
.noti-msg.unread .noti-title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--red, #ef4444);
  margin-right: 6px;
  vertical-align: middle;
}
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
