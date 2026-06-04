<template>
  <nav class="sidebar" :class="{ collapsed: collapsed && !isMobile, 'mobile-open': isMobile && mobileOpen }">
    <div class="sidebar-brand" @click="$router.push('/')">
      <div class="brand-icon">⚡</div>
      <div class="brand-text" v-show="!collapsed || isMobile">
        <span class="brand-name">RAGForge</span>
        <span class="brand-ver">v1.0</span>
      </div>
    </div>
    <div class="sidebar-nav">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: $route.path === item.path || (item.path !== '/' && $route.path.startsWith(item.path)) }"
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

const router = useRouter()
const route = useRoute()
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

const navItems = [
  { path: '/', icon: '🏠', label: '驾驶舱' },
  { path: '/knowledge', icon: '📁', label: '知识库管理' },
  { path: '/debug', icon: '🔍', label: '检索调试台' },
  { path: '/perf-probe', icon: '⏱', label: '性能诊断' },
  { path: '/eval', icon: '🧪', label: '评测实验室' },
  { path: '/api-gateway', icon: '🔌', label: 'API 网关' },
]
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

.sidebar-brand {
  padding: 18px 14px 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  border-bottom: 1px solid var(--border);
  min-height: 56px;
}
.brand-icon { font-size: 20px; flex-shrink: 0; }
.brand-text { overflow: hidden; white-space: nowrap; }
.brand-name { display: block; font-weight: 700; font-size: 14px; color: var(--navy); }
.brand-ver { font-size: 10px; color: var(--text-muted); }

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
.nav-item.active { color: var(--blue); background: #dbeafe; font-weight: 600; }
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

  .sidebar-brand {
    padding-top: calc(18px + env(safe-area-inset-top));
  }

  .nav-item {
    min-height: 42px;
  }

  .sidebar.mobile-open {
    transform: translateX(0);
  }
}
</style>
