import request from './request'
import { authClient } from './auth'
import { useAuth } from '../composables/useAuth'

// ---- /api/v1：当前用户聚合 + 本地资料（不调网关）----
export function fetchMe() {
  return request.get('/me')
}

export function getProfile() {
  return request.get('/profile')
}

export function updateProfile(payload) {
  return request.put('/profile', payload)
}

// ---- /api/auth：注册与凭证（代理到网关）----
export function register(payload) {
  return authClient.post('/register', payload)
}

export function setPassword(payload) {
  return authClient.post('/credential/set-password', payload)
}

export function bindEmail(payload) {
  return authClient.post('/credential/bind-email', payload)
}

export function setUsername(payload) {
  return authClient.post('/credential/set-username', payload)
}

/** 拉取 /me 并写入鉴权状态（capabilities + 显示名兜底）。 */
export async function loadMe() {
  const { setMe } = useAuth()
  try {
    const body = await fetchMe()
    const me = body?.data ?? body
    setMe(me)
    return me
  } catch {
    return null
  }
}
