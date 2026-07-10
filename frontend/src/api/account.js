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

// 账号注销（走 /api/auth 代理到网关）
export function requestAccountDeletion(payload) {
  return authClient.post('/users/me/deletion-request', payload)
}

export function cancelAccountDeletion(payload) {
  return authClient.delete('/users/me/deletion-request', { data: payload })
}

// 协议同意（走 /api/auth 代理到网关）
export function acceptTerms(payload) {
  return authClient.post('/users/me/terms-acceptance', payload)
}

// Onboarding（走 /api/v1/me）
export function completeOnboarding() {
  return request.post('/me/onboarding-complete')
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
