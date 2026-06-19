import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../composables/useAuth'

const routes = [
  {
    path: '/login',
    component: () => import('../layouts/AuthLayout.vue'),
    meta: { layout: 'auth', public: true },
    children: [
      { path: '', name: 'Login', component: () => import('../views/auth/Login.vue'), meta: { layout: 'auth', public: true } },
    ],
  },
  {
    path: '/auth/reset',
    component: () => import('../layouts/AuthLayout.vue'),
    meta: { layout: 'auth', public: true },
    children: [
      { path: '', name: 'ResetPassword', component: () => import('../views/auth/ResetPassword.vue'), meta: { layout: 'auth', public: true } },
    ],
  },
  { path: '/403', name: 'Forbidden', component: () => import('../views/Forbidden.vue'), meta: { icon: '!', label: '无权访问' } },
  { path: '/', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { icon: '🏠', label: '驾驶舱' } },
  { path: '/knowledge', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { icon: '📁', label: '知识库管理' } },
  { path: '/knowledge/:kbId/documents', name: 'KnowledgeDocuments', component: () => import('../views/KnowledgeDocuments.vue'), meta: { icon: '📄', label: '文档列表' } },
  { path: '/document/:id', name: 'DocumentDetail', component: () => import('../views/DocumentDetail.vue'), meta: { icon: '📄', label: '文档详情' } },
  { path: '/debug', name: 'DebugConsole', component: () => import('../views/DebugConsole.vue'), meta: { icon: '🔍', label: '检索调试台' } },
  { path: '/perf-probe', name: 'PerformanceProbe', component: () => import('../views/PerformanceProbe.vue'), meta: { icon: '⏱', label: '性能诊断' } },
  { path: '/eval', name: 'EvaluationLab', component: () => import('../views/EvaluationLab.vue'), meta: { icon: '🧪', label: '评测实验室' } },
  { path: '/api-gateway', name: 'ApiGateway', component: () => import('../views/ApiGateway.vue'), meta: { icon: '🔌', label: 'API 网关' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const { isAuthenticated } = useAuth()
  if (to.meta.public) {
    if (to.name === 'Login' && isAuthenticated.value) return { path: '/' }
    return true
  }
  if (!isAuthenticated.value) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
