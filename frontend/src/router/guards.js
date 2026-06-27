import { useAuth } from '../composables/useAuth'
import { refreshAccessToken } from '../api/auth'
import { loadMe } from '../api/account'

export function installRouteGuards(router) {
  router.beforeEach(async (to) => {
    const { isAuthenticated, ragRole, scopes, setSession } = useAuth()
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
    if (!canAccessRoute(to.meta, ragRole.value, scopes.value)) {
      return { path: '/403' }
    }
    return true
  })
}

export function canAccessRoute(meta = {}, role, scopes = new Set()) {
  const requiredRoles = meta.roles || (meta.role ? [meta.role] : ['ADMIN'])
  if (!requiredRoles.includes(role)) {
    return false
  }
  if (meta.scope && !scopes.has(meta.scope)) {
    return false
  }
  return true
}
