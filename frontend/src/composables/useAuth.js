import { reactive, computed, readonly } from 'vue'

const state = reactive({
  accessToken: null,
  user: null,
  ragRole: null,
  scopes: new Set(),
  resetTicket: null,
})

const DEFAULT_ROLE_SCOPES = {
  ADMIN: [
    'rag:dashboard:read',
    'rag:kb:read',
    'rag:kb:create',
    'rag:kb:write',
    'rag:doc:read',
    'rag:doc:write',
    'rag:debug:run',
    'rag:eval:write',
    'rag:apikey:admin',
    'rag:system:admin',
    'rag:audit:read',
    'rag:user:admin',
  ],
  KB_EDITOR: [
    'rag:dashboard:read',
    'rag:kb:read',
    'rag:kb:write',
    'rag:doc:read',
    'rag:doc:write',
    'rag:debug:run',
    'rag:eval:write',
    'rag:audit:read',
  ],
  KB_VIEWER: ['rag:dashboard:read', 'rag:kb:read', 'rag:doc:read', 'rag:debug:run', 'rag:audit:read'],
}

export function useAuth() {
  return {
    state: readonly(state),
    isAuthenticated: computed(() => !!state.accessToken),
    ragRole: computed(() => state.ragRole),
    scopes: computed(() => new Set(state.scopes)),
    setSession(token, user) {
      state.accessToken = token
      state.user = user
      const claims = parseJwtClaims(token)
      const role = user?.ragRole || user?.rag_role || claims?.rag_role || null
      state.ragRole = role
      state.scopes = resolveScopes(user, claims, role)
    },
    clearSession() {
      state.accessToken = null
      state.user = null
      state.ragRole = null
      state.scopes = new Set()
    },
    setResetTicket(ticket) {
      state.resetTicket = ticket
    },
    clearResetTicket() {
      state.resetTicket = null
    },
  }
}

function resolveScopes(user, claims, role) {
  const values = []
  if (Array.isArray(user?.scopes)) values.push(...user.scopes)
  if (typeof user?.scope === 'string') values.push(...user.scope.split(/\s+/))
  if (Array.isArray(claims?.scopes)) values.push(...claims.scopes)
  if (typeof claims?.scope === 'string') values.push(...claims.scope.split(/\s+/))
  if (role === 'ADMIN' && values.some((scope) => scope === 'rag:admin:write' || scope === 'rag:admin:read')) {
    values.push(...DEFAULT_ROLE_SCOPES.ADMIN)
  } else if (role === 'KB_EDITOR' && values.some((scope) => scope === 'rag:admin:write' || scope === 'rag:admin:read')) {
    values.push(...DEFAULT_ROLE_SCOPES.KB_EDITOR)
  } else if (values.length === 0 && DEFAULT_ROLE_SCOPES[role]) {
    values.push(...DEFAULT_ROLE_SCOPES[role])
  }
  return new Set(values.filter(Boolean))
}

function parseJwtClaims(token) {
  if (!token || token.split('.').length !== 3) return null
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = payload.padEnd(payload.length + ((4 - (payload.length % 4)) % 4), '=')
    return JSON.parse(decodeURIComponent(escape(atob(padded))))
  } catch {
    return null
  }
}
