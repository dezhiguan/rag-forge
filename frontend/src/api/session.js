import { authClient, refreshAccessToken } from './auth'
import { useAuth } from '../composables/useAuth'

// ============================================================================
// 会话生命周期中枢：主动续期 + 静默刷新（跨标签页单飞） + 失败分级。
//
// - 主动续期：登录/刷新后按 expiresIn 提前定时续期，401 只是兜底路径；
// - 跨标签页：navigator.locks 保证全浏览器同一时刻只有一个 /refresh 在飞
//   （refresh token 一次性旋转，双刷会触发网关重放检测），成功后经
//   BroadcastChannel 把新 token 同步给其他标签页；
// - 失败分级：只有网关明确 401/403 才判"会话真过期"（err.authExpired=true），
//   超时/网络错/5xx 属于线路抖动，退避重试后仍失败也不清会话不踢登录。
// ============================================================================

const REFRESH_EARLY_SECONDS = 90 // 到期前提前量
const REFRESH_JITTER_MS = 15000 // 多标签页错峰抖动
const RETRY_DELAYS_MS = [800, 2000] // 网络类失败的退避重试间隔
const FRESH_WINDOW_MS = 5000 // 锁内视作"别的标签页刚刷过"的窗口

let refreshTimer = null
let refreshPromise = null
let lastAppliedAt = 0

const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('ragforge-auth') : null

if (channel) {
  channel.onmessage = (event) => {
    const msg = event.data || {}
    if (msg.type === 'session' && msg.accessToken) {
      lastAppliedAt = Date.now()
      useAuth().setSession(msg.accessToken, msg.user)
      scheduleProactiveRefresh(msg.expiresIn)
    } else if (msg.type === 'logout') {
      clearTimeout(refreshTimer)
      useAuth().clearSession()
    }
  }
}

/** 登录/续期成功后的统一入口：写会话 + 定时主动续期 + 广播给其他标签页。 */
export function applySession(session, { broadcast = true } = {}) {
  lastAppliedAt = Date.now()
  useAuth().setSession(session.accessToken, session.user)
  scheduleProactiveRefresh(session.expiresIn)
  if (broadcast && channel) {
    channel.postMessage({
      type: 'session',
      accessToken: session.accessToken,
      user: session.user,
      expiresIn: session.expiresIn,
    })
  }
  return session
}

/** 主动续期：到期前 90s（带抖动）静默换新 token，让被动 401 极少发生。 */
function scheduleProactiveRefresh(expiresIn) {
  clearTimeout(refreshTimer)
  const ttl = Number(expiresIn)
  if (!Number.isFinite(ttl) || ttl <= 0) return
  // TTL 过短时至少等一半，避免立即打刷新
  const base = Math.max((ttl - REFRESH_EARLY_SECONDS) * 1000, (ttl * 1000) / 2)
  const delay = base + Math.floor(Math.random() * REFRESH_JITTER_MS)
  refreshTimer = setTimeout(() => {
    silentRefresh().catch((err) => {
      if (err && err.authExpired) {
        handleSessionExpired()
      }
      // 网络类失败：内部已退避重试，这里静默放过，等下一次 401 兜底再试
    })
  }, delay)
}

/**
 * 静默刷新（单飞）。成功 resolve 新 accessToken；失败 reject 的错误带
 * authExpired 标记：true=会话真过期（该踢登录），false=网络抖动（别踢）。
 */
export function silentRefresh() {
  if (!refreshPromise) {
    refreshPromise = refreshWithCrossTabLock().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

function refreshWithCrossTabLock() {
  // Web Locks 跨标签页互斥；不支持的环境退化为单页单飞（有网关旋转宽限期兜底）
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return navigator.locks.request('ragforge-auth-refresh', () => doRefresh())
  }
  return doRefresh()
}

async function doRefresh() {
  // 排队等锁期间，别的标签页可能已完成续期并广播了新 token → 直接复用
  const { state } = useAuth()
  if (state.accessToken && Date.now() - lastAppliedAt < FRESH_WINDOW_MS) {
    return state.accessToken
  }
  let lastError = null
  for (let attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
    if (attempt > 0) {
      await sleep(RETRY_DELAYS_MS[attempt - 1])
    }
    try {
      const session = await refreshAccessToken()
      applySession(session)
      return session.accessToken
    } catch (err) {
      lastError = err
      if (isAuthRejection(err)) {
        throw tagError(err, true)
      }
      // 超时/断网/网关 5xx → 线路抖动，退避后重试
    }
  }
  throw tagError(lastError, false)
}

/** 只有网关明确的 401/403 才算"会话真过期"。 */
function isAuthRejection(err) {
  const status = err?.status ?? err?.response?.status
  return status === 401 || status === 403
}

function tagError(err, authExpired) {
  const error = err instanceof Error ? err : new Error(err?.message || 'refresh failed')
  error.authExpired = authExpired
  if (err && !(err instanceof Error)) {
    error.cause = err
  }
  return error
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 会话真过期：清会话、停定时器、通知其他标签页，并带回跳地址去登录页。 */
export function handleSessionExpired() {
  clearTimeout(refreshTimer)
  useAuth().clearSession()
  if (channel) {
    channel.postMessage({ type: 'logout' })
  }
  if (typeof window !== 'undefined' && !/\/login/.test(window.location.pathname)) {
    const here = window.location.pathname + window.location.search
    window.location.href = `/login?reason=expired&redirect=${encodeURIComponent(here)}`
  }
}

/** 用户主动退出：停定时器并广播，让其他标签页一起下线（跳转由调用方负责）。 */
export function notifyLoggedOut() {
  clearTimeout(refreshTimer)
  if (channel) {
    channel.postMessage({ type: 'logout' })
  }
}

// ---------------------------------------------------------------------------
// authClient 的 401 兜底：带 Bearer 的认证接口（userinfo/logout-all/credential 等）
// 过期时也走静默续期+重放。登录/刷新/重置类接口自身 401 属业务语义，不重试。
// ---------------------------------------------------------------------------
const AUTH_NO_RETRY_PREFIXES = ['/refresh', '/login', '/sms/', '/password/', '/register', '/captcha']

authClient.interceptors.response.use(undefined, (err) => {
  const config = err?.config
  const url = config?.url || ''
  const status = err?.status ?? err?.response?.status
  if (
    status === 401 &&
    config &&
    !config._retried &&
    !AUTH_NO_RETRY_PREFIXES.some((prefix) => url.startsWith(prefix))
  ) {
    config._retried = true
    return silentRefresh().then(() => authClient(config))
  }
  return Promise.reject(err)
})
