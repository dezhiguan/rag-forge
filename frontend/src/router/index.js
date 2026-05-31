import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { icon: '🏠', label: '驾驶舱' } },
  { path: '/knowledge', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { icon: '📁', label: '知识库管理' } },
  { path: '/knowledge/:kbId/documents', name: 'KnowledgeDocuments', component: () => import('../views/KnowledgeDocuments.vue'), meta: { icon: '📄', label: '文档列表' } },
  { path: '/document/:id', name: 'DocumentDetail', component: () => import('../views/DocumentDetail.vue'), meta: { icon: '📄', label: '文档详情' } },
  { path: '/debug', name: 'DebugConsole', component: () => import('../views/DebugConsole.vue'), meta: { icon: '🔍', label: '检索调试台' } },
  { path: '/perf-probe', name: 'PerformanceProbe', component: () => import('../views/PerformanceProbe.vue'), meta: { icon: '⏱', label: '性能诊断' } },
  { path: '/eval', name: 'EvaluationLab', component: () => import('../views/EvaluationLab.vue'), meta: { icon: '🧪', label: '评测实验室' } },
  { path: '/api', name: 'ApiGateway', component: () => import('../views/ApiGateway.vue'), meta: { icon: '🔌', label: 'API 网关' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
