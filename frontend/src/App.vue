<template>
  <div class="app-layout">
    <Sidebar @toggle="sidebarCollapsed = !sidebarCollapsed" />
    <main class="main-content" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <div class="top-bar">
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
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from './components/Sidebar.vue'

const sidebarCollapsed = ref(true)
const route = useRoute()

const currentPage = computed(() => ({
  icon: route.meta?.icon || '',
  label: route.meta?.label || '',
}))
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
}

* { margin: 0; padding: 0; box-sizing: border-box; }

body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  color: var(--text);
  background: #fff;
  line-height: 1.75;
  font-size: 15px;
  -webkit-font-smoothing: antialiased;
}

.app-layout { display: flex; min-height: 100vh; }

.main-content {
  flex: 1;
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
  background: #f1f5f9;
  border-bottom: 1px solid var(--border);
}
.top-bar-icon { font-size: 14px; }
.top-bar-title { font-size: 15px; font-weight: 600; color: #334155; }

.page-fade-enter-active, .page-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.page-fade-enter-from { opacity: 0; transform: translateY(8px); }
.page-fade-leave-to { opacity: 0; transform: translateY(-8px); }

.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 600;
}
.badge-green { background: #d1fae5; color: #065f46; }
.badge-amber { background: #fef3c7; color: #92400e; }
.badge-red { background: #fee2e2; color: #991b1b; }

.page-body { padding: 20px 28px 40px; }

.link-action {
  color: var(--blue);
  cursor: pointer;
  font-weight: 500;
}
.link-action:hover { color: #2563eb; text-decoration: underline; }
.link-action.danger { color: var(--red); }
.link-action.danger:hover { color: #dc2626; }
</style>
