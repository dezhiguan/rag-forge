<template>
  <div class="org-switch" :class="{ collapsed }">
    <button class="org-trigger" :title="nameOf(current)" @click.stop="open = !open">
      <span class="org-ava" :style="{ background: avatarColor(current) }">{{ initial(current) }}</span>
      <span v-show="!collapsed" class="org-meta">
        <span class="org-name">{{ nameOf(current) }}</span>
        <span class="org-sub">{{ current.personal ? '个人组织' : roleLabel(current.myRole) }}</span>
      </span>
      <span v-show="!collapsed" class="org-caret">⇅</span>
    </button>

    <div v-if="open" class="org-menu">
      <div class="menu-label">切换组织（全局生效）</div>
      <div class="org-list">
        <button
          v-for="o in orgs"
          :key="o.id ?? 'personal'"
          class="org-opt"
          :class="{ active: o.id === current.id }"
          :title="nameOf(o)"
          @click="select(o)"
        >
          <span class="org-ava sm" :style="{ background: avatarColor(o) }">{{ initial(o) }}</span>
          <span class="org-meta">
            <span class="org-name" :title="nameOf(o)">{{ nameOf(o) }}</span>
            <span class="org-sub">{{ o.personal ? '个人组织' : roleLabel(o.myRole) }}</span>
          </span>
          <span class="check">{{ o.id === current.id ? '✓' : '' }}</span>
        </button>
      </div>

      <template v-if="isAdmin">
        <div class="menu-sep"></div>
        <div class="menu-label">平台管理</div>
        <button class="org-opt" :class="{ active: current.platform }" @click="selectPlatform">
          <span class="org-ava sm" style="background:#1e293b">∞</span>
          <span class="org-meta">
            <span class="org-name">全平台视图</span>
            <span class="org-sub">超管 · 破玻璃(留审计)</span>
          </span>
          <span class="check">{{ current.platform ? '✓' : '' }}</span>
        </button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useOrg, PLATFORM_ID } from '../composables/useOrg'
import { useAuth } from '../composables/useAuth'
import { confirm as confirmDialog } from '../composables/useConfirm'

// 破玻璃访问理由的存储 key（request.js 读取并注入 X-Admin-Override-Reason）
const ADMIN_OVERRIDE_REASON_KEY = 'ragforge.adminOverrideReason'

defineProps({ collapsed: { type: Boolean, default: false } })

const { orgs, current, load, setCurrent } = useOrg()
const { ragRole, isAuthenticated, userDisplayName } = useAuth()
const isAdmin = computed(() => ragRole.value === 'ADMIN')
const open = ref(false)

// 个人组织名与账号菜单同口径（显示名优先），如「官德志 的个人组织」。
function nameOf(o) {
  return o && o.personal ? `${userDisplayName.value} 的个人组织` : (o ? o.name : '')
}

const PALETTE = ['#2563eb', '#15803d', '#7c3aed', '#db2777', '#0ea5e9', '#f59e0b']
function avatarColor(o) {
  if (o.personal) return '#2563eb'
  if (o.platform) return '#1e293b'
  const key = (Number(o.id) || 0) % PALETTE.length
  return PALETTE[key]
}
function initial(o) {
  if (o && o.platform) return '∞'
  return (nameOf(o) || '?').trim().charAt(0).toUpperCase()
}
function roleLabel(role) {
  return { OWNER: 'OWNER · 你拥有', ADMIN: 'ADMIN · 管理员', MEMBER: 'MEMBER · 成员' }[role] || '组织'
}

function select(o) {
  open.value = false
  if (o.id === current.value.id) return
  // 离开平台视图即结束破玻璃：清除已存的访问理由，避免残留到普通组织上下文。
  try { localStorage.removeItem(ADMIN_OVERRIDE_REASON_KEY) } catch { /* ignore */ }
  setCurrent(o.id ?? null)
  // 切换组织是全局上下文变更：整页重载，确保所有页面带新的 X-Org-Id 重新取数。
  window.location.reload()
}

// 进入全平台视图 = 超管破玻璃:默认不激活,必须显式确认并填写访问理由(留审计)。
// 超管默认只看自己组织；仅在此主动破玻璃后才跨组织访问全平台数据。
async function selectPlatform() {
  open.value = false
  if (current.value.platform) return
  const reason = await confirmDialog({
    title: '进入全平台视图(超管破玻璃)',
    message: '你将以超管身份跨组织访问全平台数据,本次访问全程审计。请填写访问理由:',
    input: true,
    inputPlaceholder: '如:排查组织质量下降 / 客户支持工单 #123 / 安全事件核查',
    confirmText: '确认进入',
  })
  const r = typeof reason === 'string' ? reason.trim() : ''
  if (!r) return // 取消或未填理由 → 默认不激活,保持当前组织
  try { localStorage.setItem(ADMIN_OVERRIDE_REASON_KEY, r.slice(0, 200)) } catch { /* ignore */ }
  setCurrent(PLATFORM_ID)
  window.location.reload()
}


function onDocClick() {
  open.value = false
}

// 等鉴权就绪再拉组织列表（避免早于会话恢复触发 401）；传 isAdmin 以清除非超管的残留 platform 选择。
watch(isAuthenticated, (ok) => { if (ok) load({ isAdmin: isAdmin.value }) }, { immediate: true })
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<style scoped>
.org-switch {
  position: relative;
  padding: 12px 10px 10px;
  border-bottom: 1px solid var(--border);
}
.org-switch.collapsed {
  padding: 12px 8px 10px;
}
.org-trigger {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 11px;
  padding: 8px 9px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.org-trigger:hover {
  border-color: var(--primary-border);
  box-shadow: var(--shadow-sm);
}
.org-ava {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 13px;
  flex-shrink: 0;
}
.org-ava.sm {
  width: 30px;
  height: 30px;
}
.org-meta {
  flex: 1;
  min-width: 0;
  line-height: 1.2;
  display: flex;
  flex-direction: column;
}
.org-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--navy);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.org-sub {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 1px;
}
.org-caret {
  color: var(--gray);
  font-size: 13px;
  flex-shrink: 0;
}
.org-menu {
  position: absolute;
  top: calc(100% - 4px);
  left: 10px;
  right: 10px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 13px;
  box-shadow: var(--shadow-md);
  padding: 7px;
  z-index: 60;
}
/* 组织多时列表内部滚动，保证下方「平台管理 / 全平台视图」始终可见 */
.org-list {
  max-height: 320px;
  overflow-y: auto;
  overflow-x: hidden;
}
.menu-label {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--text-muted);
  letter-spacing: 0.4px;
  padding: 7px 9px 5px;
}
.org-opt {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  background: none;
  border: none;
  padding: 8px 9px;
  border-radius: 9px;
  cursor: pointer;
  text-align: left;
}
.org-opt:hover {
  background: #f6f8fb;
}
.org-opt.active {
  background: var(--primary-soft);
}
.check {
  margin-left: auto;
  color: var(--primary);
  font-weight: 700;
  font-size: 12px;
}
.menu-sep {
  height: 1px;
  background: var(--border);
  margin: 5px 4px;
}
</style>
