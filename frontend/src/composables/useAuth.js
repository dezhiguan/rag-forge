import { reactive, computed, readonly } from 'vue'

const state = reactive({
  accessToken: null,
  user: null,
  ragRole: null,
  scopes: new Set(),
  resetTicket: null,
  me: null,
  capabilities: [],
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
  // 普通注册用户：可看驾驶舱/知识库/检索/应答，并对自有库读写（真正授权以后端为准）
  USER: [
    'rag:dashboard:read',
    'rag:kb:read',
    'rag:kb:create',
    'rag:kb:write',
    'rag:doc:read',
    'rag:doc:write',
    'rag:debug:run',
  ],
}

export function useAuth() {
  return {
    state: readonly(state),
    isAuthenticated: computed(() => !!state.accessToken),
    // 统一的用户标识：显示名优先 → 用户名 → 脱敏手机号（组织切换器与账号菜单共用，保持一致）。
    userDisplayName: computed(() => {
      const me = state.me || {}
      const user = state.user || {}
      return (
        me.displayName ||
        user.displayName ||
        me.username ||
        user.username ||
        me.maskedPhone ||
        user.account ||
        'RAGForge 用户'
      )
    }),
    ragRole: computed(() => state.ragRole),
    scopes: computed(() => new Set(state.scopes)),
    capabilities: computed(() => state.capabilities),
    hasCapability: (cap) => Array.isArray(state.capabilities) && state.capabilities.includes(cap),
    setSession(token, user) {
      state.accessToken = token
      state.user = user
      const claims = parseJwtClaims(token)
      // 登录响应的 user 常不带 ragRole，claims 也可能没有；任何已登录用户至少是 USER，
      // 兜底为 USER 让 scopes 立即含 rag:dashboard:read，避免跳首页被守卫拦到 /403。
      const role = user?.ragRole || user?.rag_role || claims?.rag_role || 'USER'
      state.ragRole = role
      state.scopes = resolveScopes(user, claims, role)
    },
    setMe(me) {
      state.me = me || null
      state.capabilities = me?.capabilities || []
      if (me?.ragRole) state.ragRole = me.ragRole
      if (me?.displayName) {
        state.user = { ...(state.user || {}), displayName: me.displayName }
      }
      // /me 返回真实角色后重算 scopes：setSession 时 role 可能还是兜底 USER，
      // 这里据实补全（如 ADMIN/KB_EDITOR），保证菜单/路由权限正确。
      state.scopes = resolveScopes(state.user, parseJwtClaims(state.accessToken), state.ragRole)
    },
    clearSession() {
      state.accessToken = null
      state.user = null
      state.ragRole = null
      state.scopes = new Set()
      state.me = null
      state.capabilities = []
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
  } else if (role === 'USER') {
    values.push(...DEFAULT_ROLE_SCOPES.USER)
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
