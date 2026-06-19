<template>
  <router-view v-if="isAuthLayout" v-slot="{ Component }">
    <transition name="page-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
  <div v-else class="app-layout">
    <div v-if="mobileMenuOpen" class="mobile-overlay" @click="closeMobileMenu" />
    <Sidebar
      :mobile-open="mobileMenuOpen"
      @toggle="sidebarCollapsed = !sidebarCollapsed"
      @close-mobile="closeMobileMenu"
    />
    <main class="main-content" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <div class="top-bar">
        <span class="hamburger" @click="toggleMobileMenu">☰</span>
        <span class="top-bar-icon">{{ currentPage.icon }}</span>
        <span class="top-bar-title">{{ currentPage.label }}</span>
      </div>
      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from './components/Sidebar.vue'

const sidebarCollapsed = ref(false)
const mobileMenuOpen = ref(false)
const route = useRoute()

const isAuthLayout = computed(() => route.meta?.layout === 'auth')

const currentPage = computed(() => ({
  icon: route.meta?.icon || '',
  label: route.meta?.label || '',
}))

function toggleMobileMenu() {
  mobileMenuOpen.value = !mobileMenuOpen.value
}

function closeMobileMenu() {
  mobileMenuOpen.value = false
}

watch(() => route.path, () => {
  mobileMenuOpen.value = false
})
</script>

<style>
:root {
  --navy: #0f172a;
  --slate: #1e293b;
  --gray: #475569;
  --light: #f1f5f9;
  --blue: #3b82f6;
  --cyan: #06b6d4;
  --green: #10b981;
  --amber: #f59e0b;
  --red: #ef4444;
  --purple: #8b5cf6;
  --border: #e2e8f0;
  --text: #1e293b;
  --text-muted: #64748b;
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-full: 999px;
}

* { margin: 0; padding: 0; box-sizing: border-box; }

body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  color: var(--text);
  background: #f8fafc;
  line-height: 1.75;
  font-size: 15px;
  -webkit-font-smoothing: antialiased;
}

.app-layout { display: flex; min-height: 100vh; }

.main-content {
  flex: 1;
  min-width: 0;
  margin-left: 200px;
  min-height: 100vh;
  background: #fafbfc;
  transition: margin-left 0.25s ease;
}
.main-content.sidebar-collapsed { margin-left: 56px; }

.top-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 28px;
  height: 44px;
  background: #fff;
  border-bottom: 1px solid var(--border);
}
.top-bar-icon { font-size: 14px; }
.top-bar-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 600;
  color: #334155;
}

.page-fade-enter-active, .page-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.page-fade-enter-from { opacity: 0; transform: translateY(8px); }
.page-fade-leave-to { opacity: 0; transform: translateY(-8px); }

.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-weight: 600;
}
.badge-green { background: #d1fae5; color: #065f46; }
.badge-amber { background: #fef3c7; color: #92400e; }
.badge-red { background: #fee2e2; color: #991b1b; }

/* 全局表格表头基础样式 */
.data-table thead th {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  background: #f8fafc;
  text-transform: none;
  letter-spacing: 0;
}

/* 全局空状态/加载态 */
.state-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 56px 0;
  color: var(--text-muted);
  font-size: 13px;
}
.state-hint .state-icon { font-size: 32px; opacity: 0.4; }
.state-hint .state-title { font-weight: 600; color: var(--slate); }
.state-hint .state-desc { font-size: 12px; }

.page-body { padding: 20px 28px 40px; }

.link-action {
  color: var(--blue);
  cursor: pointer;
  font-weight: 500;
}
.link-action:hover { color: #2563eb; text-decoration: underline; }
.link-action.danger { color: var(--red); }
.link-action.danger:hover { color: #dc2626; }
.link-action.is-disabled,
.link-action.danger.is-disabled {
  color: var(--text-muted);
  opacity: 0.55;
  cursor: not-allowed;
  pointer-events: none;
  text-decoration: none;
}
.link-btn:disabled,
.link-btn.danger:disabled {
  color: var(--text-muted);
  border-color: var(--border);
  background: #f8fafc;
  opacity: 0.65;
  cursor: not-allowed;
}

.hamburger {
  display: none;
  font-size: 18px;
  cursor: pointer;
  padding: 4px 8px;
  margin-right: 4px;
  border-radius: 4px;
  user-select: none;
}
.hamburger:hover { background: rgba(0,0,0,0.06); }

.mobile-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  z-index: 99;
}

/* ====== 移动端响应式 ====== */
@media (max-width: 768px) {
  body {
    overflow-x: hidden;
  }

  .hamburger { display: inline-block; }

  .mobile-overlay { display: block; }

  .main-content {
    margin-left: 0 !important;
  }

  .top-bar {
    position: sticky;
    top: 0;
    z-index: 50;
    padding: 0 16px;
    justify-content: flex-start;
  }

  .hamburger {
    min-width: 36px;
    min-height: 36px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .page-body {
    padding: 16px 12px 32px;
    max-width: 100vw;
    overflow-x: hidden;
  }

  .data-table thead th,
  .data-table tbody td {
    padding: 8px 10px;
    font-size: 11px;
  }

  .table-card,
  .table-wrap {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }

  input,
  select,
  textarea {
    font-size: 16px;
  }

  .modal-mask {
    align-items: flex-end;
    padding: 0;
  }

  .modal {
    width: 100vw;
    max-width: none;
    max-height: 92vh;
    overflow-y: auto;
    border-radius: 12px 12px 0 0;
    padding: 18px 16px;
  }

  .modal-actions {
    position: sticky;
    bottom: 0;
    margin: 16px -16px -18px;
    padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
    background: #fff;
    border-top: 1px solid var(--border);
  }
}
</style>
