import { useAuth } from '../composables/useAuth'
import { useOrg } from '../composables/useOrg'
// 经 session.js 单飞刷新：避免守卫与 401 拦截器并发双刷触发网关旋转重放检测
import { silentRefresh } from '../api/session'
import { loadMe } from '../api/account'

export function installRouteGuards(router) {
  router.beforeEach(async (to) => {
    const { isAuthenticated, ragRole, scopes } = useAuth()
    const { current, load, state: orgState } = useOrg()
    if (to.meta.public) {
      if (to.name === 'Login' && isAuthenticated.value) return { path: '/' }
      return true
    }
    if (!isAuthenticated.value) {
      try {
        await silentRefresh() // 内部已写会话并安排主动续期
        await loadMe()
      } catch {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
    if (to.path === '/403') {
      return true
    }
    // 组织维度路由：直达(刷新/外链)时组织 myRole 可能尚未加载，先确保就绪再判权，
    // 否则会把有权限的 OWNER/ADMIN 误判到 /403。
    if (to.meta?.orgRoles && !orgState.loaded) {
      try {
        await load({ isAdmin: ragRole.value === 'ADMIN' })
      } catch {
        /* 组织加载失败不阻断，交由 canAccessRoute 兜底 */
      }
    }
    if (!canAccessRoute(to.meta, ragRole.value, scopes.value, current.value?.myRole)) {
      return { path: '/403' }
    }
    return true
  })
}

/**
 * 路由可见性判定。
 * - 平台超管(ragRole=ADMIN)：可见所有 tab。
 * - meta.orgRoles 定义：按当前组织角色(OWNER/ADMIN/MEMBER)判定（组织维度，忽略平台 scope）。
 * - 否则：按平台角色 meta.roles + meta.scope 判定（兼容旧配置）。
 */
export function canAccessRoute(meta = {}, role, scopes = new Set(), orgRole) {
  if (role === 'ADMIN') {
    return true
  }
  if (meta.orgRoles) {
    return !!orgRole && meta.orgRoles.includes(orgRole)
  }
  const requiredRoles = meta.roles || (meta.role ? [meta.role] : ['ADMIN'])
  if (!requiredRoles.includes(role)) {
    return false
  }
  if (meta.scope && !scopes.has(meta.scope)) {
    return false
  }
  return true
}
