import { createRouter, createWebHistory } from 'vue-router'
import { installRouteGuards } from './guards'

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
  { path: '/403', name: 'Forbidden', component: () => import('../views/Forbidden.vue'), meta: { icon: '!', label: '无权访问', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER'] } },
  { path: '/', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { icon: '🏠', label: '驾驶舱', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER'], scope: 'rag:dashboard:read' } },
  { path: '/knowledge', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { icon: '📁', label: '知识库管理', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER'], scope: 'rag:kb:read' } },
  { path: '/knowledge/:kbId/documents', name: 'KnowledgeDocuments', component: () => import('../views/KnowledgeDocuments.vue'), meta: { icon: '📄', label: '文档列表', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER'], scope: 'rag:doc:read' } },
  { path: '/uploads/wizard', name: 'UploadWizard', component: () => import('../views/UploadWizard.vue'), meta: { icon: '⬆', label: '上传向导', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:doc:write' } },
  { path: '/document/:id', name: 'DocumentDetail', component: () => import('../views/DocumentDetail.vue'), meta: { icon: '📄', label: '文档详情', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER'], scope: 'rag:doc:read' } },
  { path: '/debug', name: 'DebugConsole', component: () => import('../views/DebugConsole.vue'), meta: { icon: '🔍', label: '检索调试台', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER'], scope: 'rag:debug:run' } },
  { path: '/perf-probe', name: 'PerformanceProbe', component: () => import('../views/PerformanceProbe.vue'), meta: { icon: '⏱', label: '性能诊断', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/eval', name: 'EvaluationLab', component: () => import('../views/EvaluationLab.vue'), meta: { icon: '🧪', label: '评测实验室', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/api', name: 'ApiGateway', component: () => import('../views/ApiGateway.vue'), meta: { icon: '🔌', label: 'API 网关', role: 'ADMIN', scope: 'rag:apikey:admin' } },
  { path: '/api-gateway', redirect: '/api', meta: { role: 'ADMIN', scope: 'rag:apikey:admin' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

installRouteGuards(router)

export default router
