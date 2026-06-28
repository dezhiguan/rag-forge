<template>
  <nav class="sidebar" :class="{ collapsed: collapsed && !isMobile, 'mobile-open': isMobile && mobileOpen }">
    <div class="sidebar-user" v-show="!collapsed || isMobile">
      <UserMenu />
    </div>
    <div class="sidebar-nav">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isNavActive(item.path) }"
        :title="collapsed && !isMobile ? item.label : ''"
        @click="onNavClick"
      >
        <span class="nav-icon">{{ item.icon }}</span>
        <span class="nav-label" v-show="!collapsed || isMobile">{{ item.label }}</span>
      </router-link>
    </div>
    <div class="sidebar-footer" v-show="!collapsed || isMobile">
      <div class="status-dot"></div>
      <span class="status-text">服务运行中</span>
    </div>
    <div v-if="!isMobile" class="collapse-btn" @click="toggleCollapse" :title="collapsed ? '展开' : '收起'">
      {{ collapsed ? '▶' : '◀' }}
    </div>
  </nav>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuth } from '../composables/useAuth'
import { canAccessRoute } from '../router/guards'
import UserMenu from './UserMenu.vue'

const router = useRouter()
const route = useRoute()
const { ragRole, scopes } = useAuth()
const collapsed = ref(false)
const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)

const props = defineProps({
  mobileOpen: { type: Boolean, default: false },
})

const emit = defineEmits(['toggle', 'close-mobile'])

const isMobile = computed(() => windowWidth.value <= 768)

function onResize() {
  windowWidth.value = window.innerWidth
}

onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))

function toggleCollapse() {
  collapsed.value = !collapsed.value
  emit('toggle')
}

function onNavClick() {
  if (isMobile.value) {
    emit('close-mobile')
  }
}

function isNavActive(itemPath) {
  const current = route.path
  if (current === itemPath) return true
  if (itemPath === '/') return false
  return current.startsWith(`${itemPath}/`)
}

const ALL_NAV_ITEMS = [
  { path: '/', icon: '🏠', label: '驾驶舱', meta: { roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:dashboard:read' } },
  { path: '/knowledge', icon: '📁', label: '知识库管理', meta: { roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:kb:read' } },
  { path: '/orgs', icon: '🏢', label: '组织', meta: { roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'] } },
  { path: '/debug', icon: '🔍', label: '检索调试台', meta: { roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:debug:run' } },
  { path: '/answer', icon: '💬', label: '应答调试台', meta: { roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:debug:run' } },
  { path: '/perf-probe', icon: '⏱', label: '性能诊断', meta: { roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/eval', icon: '🧪', label: '评测实验室', meta: { roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/evaluation/quality', icon: '📈', label: '质量看板', meta: { roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/models', icon: '🧠', label: '模型 & 成本', meta: { role: 'ADMIN' } },
  { path: '/api', icon: '🔌', label: 'API 网关', meta: { role: 'ADMIN', scope: 'rag:apikey:admin' } },
]

const navItems = computed(() => ALL_NAV_ITEMS.filter((item) => canAccessRoute(item.meta, ragRole.value, scopes.value)))
</script>

<style scoped>
.sidebar {
  position: fixed;
  left: 0; top: 0; bottom: 0;
  width: 200px;
  background: #f1f5f9;
  color: var(--text);
  display: flex;
  flex-direction: column;
  z-index: 100;
  border-right: 1px solid var(--border);
  transition: width 0.25s ease;
}
.sidebar.collapsed { width: 56px; }

.sidebar-user {
  padding: 14px 10px 10px;
  border-bottom: 1px solid var(--border);
}

.sidebar-nav {
  flex: 1;
  padding: 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  color: var(--gray);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.15s ease;
  white-space: nowrap;
  overflow: hidden;
}
.nav-item:hover { color: var(--navy); background: rgba(0,0,0,0.04); }
.nav-item.active { color: var(--primary); background: var(--primary-soft); font-weight: 600; }
.nav-icon { font-size: 16px; width: 24px; text-align: center; flex-shrink: 0; }
.nav-label { flex: 1; }

.sidebar-footer {
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 8px;
}
.status-dot {
  width: 7px; height: 7px;
  background: #10b981;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-text { font-size: 11px; color: var(--text-muted); }

.collapse-btn {
  position: absolute;
  right: -12px; top: 50%;
  transform: translateY(-50%);
  width: 24px; height: 24px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 10px;
  color: var(--text-muted);
  transition: all 0.15s ease;
  z-index: 10;
}
.collapse-btn:hover { background: var(--light); color: var(--text); box-shadow: 0 2px 6px rgba(0,0,0,0.1); }

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  .sidebar {
    width: 220px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    z-index: 101;
    overflow-y: auto;
    padding-bottom: env(safe-area-inset-bottom);
    box-shadow: 4px 0 20px rgba(0,0,0,0.15);
  }

  .sidebar-user {
    padding-top: calc(14px + env(safe-area-inset-top));
  }

  .nav-item {
    min-height: 42px;
  }

  .sidebar.mobile-open {
    transform: translateX(0);
  }
}
</style>
