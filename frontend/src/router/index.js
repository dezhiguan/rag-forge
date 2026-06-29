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
  {
    path: '/auth/register',
    component: () => import('../layouts/AuthLayout.vue'),
    meta: { layout: 'auth', public: true },
    children: [
      { path: '', name: 'Register', component: () => import('../views/auth/Register.vue'), meta: { layout: 'auth', public: true } },
    ],
  },
  { path: '/403', name: 'Forbidden', component: () => import('../views/Forbidden.vue'), meta: { icon: '!', label: '无权访问', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'] } },
  { path: '/', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { icon: '🏠', label: '驾驶舱', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:dashboard:read' } },
  { path: '/account', name: 'AccountSettings', component: () => import('../views/AccountSettings.vue'), meta: { icon: '⚙', label: '账号设置', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'] } },
  { path: '/knowledge', name: 'KnowledgeBase', component: () => import('../views/KnowledgeBase.vue'), meta: { icon: '📁', label: '知识库管理', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:kb:read' } },
  { path: '/knowledge/:kbId/documents', name: 'KnowledgeDocuments', component: () => import('../views/KnowledgeDocuments.vue'), meta: { icon: '📄', label: '文档列表', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:doc:read' } },
  { path: '/orgs', name: 'OrgList', component: () => import('../views/OrgList.vue'), meta: { icon: '🏢', label: '组织', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'] } },
  { path: '/orgs/manage', name: 'Organizations', component: () => import('../views/Organizations.vue'), meta: { icon: '🏢', label: '组织管理', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'] } },
  { path: '/uploads/wizard', name: 'UploadWizard', component: () => import('../views/UploadWizard.vue'), meta: { icon: '⬆', label: '上传向导', roles: ['ADMIN', 'KB_EDITOR', 'USER'], scope: 'rag:doc:write' } },
  { path: '/document/:id', name: 'DocumentDetail', component: () => import('../views/DocumentDetail.vue'), meta: { icon: '📄', label: '文档详情', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:doc:read' } },
  { path: '/debug', name: 'DebugConsole', component: () => import('../views/DebugConsole.vue'), meta: { icon: '🔍', label: '检索调试台', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:debug:run' } },
  { path: '/answer', name: 'AnswerPlayground', component: () => import('../views/AnswerPlayground.vue'), meta: { icon: '💬', label: '应答调试台', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'], scope: 'rag:debug:run' } },
  { path: '/perf-probe', name: 'PerformanceProbe', component: () => import('../views/PerformanceProbe.vue'), meta: { icon: '⏱', label: '性能诊断', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/eval', name: 'EvaluationLab', component: () => import('../views/EvaluationLab.vue'), meta: { icon: '🧪', label: '评测实验室', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/evaluation/quality', name: 'EvaluationQuality', component: () => import('../views/EvaluationQuality.vue'), meta: { icon: '📈', label: '质量看板', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/evaluation/quality/case/:id', name: 'EvaluationQualityCase', component: () => import('../views/EvaluationQualityCase.vue'), meta: { icon: '🧪', label: '案例详情', roles: ['ADMIN', 'KB_EDITOR'], scope: 'rag:eval:write' } },
  { path: '/models', name: 'ModelCostCenter', component: () => import('../views/ModelCostCenter.vue'), meta: { icon: '🧠', label: '模型 & 成本', role: 'ADMIN' } },
  { path: '/api', name: 'DeveloperCenter', component: () => import('../views/DeveloperCenter.vue'), meta: { icon: '🔌', label: '开发者中心', roles: ['ADMIN', 'KB_EDITOR', 'KB_VIEWER', 'USER'] } },
  { path: '/api-gateway', redirect: '/api' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

installRouteGuards(router)

export default router
