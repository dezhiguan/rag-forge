<template>
  <div class="user-menu" ref="menuRef">
    <button type="button" class="avatar-btn" aria-haspopup="menu" :aria-expanded="open" @click="open = !open">
      <span class="avatar">{{ initials }}</span>
      <span class="user-meta">
        <span class="user-name">{{ displayName }}</span>
        <span class="tenant">{{ orgLabel }}</span>
      </span>
    </button>

    <div v-if="open" class="menu-panel" role="menu">
      <button type="button" role="menuitem" class="menu-item" @click="goAccount('profile')">个人设置</button>
      <button type="button" role="menuitem" class="menu-item" @click="goAccount('security')">安全中心</button>
      <button type="button" role="menuitem" class="menu-item" @click="goOrgs">组织管理</button>
      <button type="button" role="menuitem" class="menu-item danger" @click="handleLogout">退出登录</button>
      <button type="button" role="menuitem" class="menu-item danger" @click="showLogoutAll = true">退出所有设备</button>
    </div>

    <LogoutAllDialog
      v-if="showLogoutAll"
      :loading="logoutAllLoading"
      @cancel="showLogoutAll = false"
      @confirm="handleLogoutAll"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { logout, logoutAll } from '../api/auth'
import { notifyLoggedOut } from '../api/session'
import { useAuth } from '../composables/useAuth'
import { useOrg } from '../composables/useOrg'
import LogoutAllDialog from './LogoutAllDialog.vue'
import { confirm as confirmDialog } from '../composables/useConfirm'

const router = useRouter()
const { clearSession, userDisplayName } = useAuth()
const { current, isPersonal } = useOrg()
const open = ref(false)
const showLogoutAll = ref(false)
const logoutAllLoading = ref(false)
const menuRef = ref(null)

// 与组织切换器同口径（显示名优先 → 用户名 → 脱敏手机号）
const displayName = userDisplayName
// 副标题展示当前所在组织；个人组织统一显示“个人组织”
const orgLabel = computed(() => (isPersonal.value ? '个人组织' : current.value.name))
const initials = computed(() => displayName.value.slice(0, 1).toUpperCase())

function goAccount(tab) {
  open.value = false
  router.push({ path: '/account', query: { tab } })
}

function goOrgs() {
  open.value = false
  router.push('/orgs')
}

async function handleLogout() {
  open.value = false
  const ok = await confirmDialog({
    title: '退出登录',
    message: '确认退出当前 RAGForge 登录？',
    confirmText: '退出',
    cancelText: '取消',
  })
  if (!ok) return
  try {
    await logout()
  } finally {
    clearSession()
    notifyLoggedOut() // 停续期定时器并广播，其他标签页一起下线
    router.replace('/login')
  }
}

async function handleLogoutAll(password) {
  logoutAllLoading.value = true
  try {
    await logoutAll({ password })
  } finally {
    logoutAllLoading.value = false
    showLogoutAll.value = false
    open.value = false
    clearSession()
    notifyLoggedOut()
    router.replace('/login')
  }
}

function onDocumentClick(event) {
  if (!menuRef.value?.contains(event.target)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocumentClick))
onUnmounted(() => document.removeEventListener('click', onDocumentClick))
</script>

<style scoped>
.user-menu {
  position: relative;
}

.avatar-btn {
  height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  padding: 0 10px 0 5px;
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.avatar-btn:hover { border-color: #cbd5e1; box-shadow: var(--shadow-sm); }

.avatar {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #0f172a;
  color: #fff;
  font-size: 12px;
  font-weight: 800;
}

.user-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.15;
}

.user-name,
.tenant {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-name {
  color: #0f172a;
  font-size: 12px;
  font-weight: 700;
}

.tenant {
  color: #64748b;
  font-size: 10px;
}

.user-name, .tenant { max-width: 100%; }

.menu-panel {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  left: auto;
  z-index: 200;
  min-width: 140px;
  padding: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.16);
}

.menu-item {
  width: 100%;
  height: 34px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #334155;
  text-align: left;
  padding: 0 10px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
}

.menu-item:hover {
  background: #f8fafc;
}

.menu-item.danger {
  color: #dc2626;
}
</style>
