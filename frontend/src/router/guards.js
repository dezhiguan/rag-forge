import { useAuth } from '../composables/useAuth'
import { useOrg } from '../composables/useOrg'
import { refreshAccessToken } from '../api/auth'
import { loadMe } from '../api/account'

export function installRouteGuards(router) {
  router.beforeEach(async (to) => {
    const { isAuthenticated, ragRole, scopes, setSession } = useAuth()
    const { current } = useOrg()
    if (to.meta.public) {
      if (to.name === 'Login' && isAuthenticated.value) return { path: '/' }
      return true
    }
    if (!isAuthenticated.value) {
      try {
        const session = await refreshAccessToken()
        setSession(session.accessToken, session.user)
        await loadMe()
      } catch {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
    if (to.path === '/403') {
      return true
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
