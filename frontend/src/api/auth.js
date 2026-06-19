import axios from 'axios'
import { useAuth } from '../composables/useAuth'

export const authClient = axios.create({
  baseURL: '/api/auth',
  timeout: 15000,
  withCredentials: true,
})

function readCookie(name) {
  if (typeof document === 'undefined') return ''
  return document.cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(`${name}=`))
    ?.slice(name.length + 1) || ''
}

authClient.interceptors.request.use((config) => {
  const { state } = useAuth()
  if (state.accessToken) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${state.accessToken}`
  }
  const method = (config.method || 'get').toLowerCase()
  if (!['get', 'head', 'options'].includes(method)) {
    const csrf = readCookie('rf_csrf')
    if (csrf) {
      config.headers = config.headers || {}
      config.headers['X-CSRF-Token'] = decodeURIComponent(csrf)
    }
  }
  return config
})

authClient.interceptors.response.use(
  (response) => response.data,
  (error) => Promise.reject(normalizeAuthError(error))
)

export async function loginByPassword(payload) {
  return normalizeLoginResponse(await authClient.post('/login', payload))
}

export async function loginByMobile(payload) {
  return normalizeLoginResponse(await authClient.post('/login-mobile', payload))
}

export function sendSmsCode({ phone, scene = 'login' }) {
  return authClient.post('/sms/send', { phone, scene })
}

export async function refreshAccessToken() {
  return normalizeLoginResponse(await authClient.post('/refresh'))
}

export function logout() {
  return authClient.post('/logout')
}

export function logoutAll({ password }) {
  return authClient.post('/logout-all', { password })
}

export function resetPasswordInit(payload) {
  return authClient.post('/password/reset/init', payload)
}

export function resetPasswordVerify(payload) {
  return authClient.post('/password/reset/verify', payload)
}

export async function resetPasswordConfirm(payload) {
  return normalizeLoginResponse(await authClient.post('/password/reset/confirm', payload))
}

export function userinfo(accessToken) {
  return authClient.get('/userinfo', {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  })
}

function normalizeLoginResponse(body) {
  const data = body?.data ?? body ?? {}
  return {
    accessToken: data.accessToken || data.access_token || data.token,
    expiresIn: data.expiresIn || data.expires_in,
    user: data.user || data.profile || null,
  }
}

function normalizeAuthError(error) {
  const body = error?.response?.data
  const payload = body?.data || body || {}
  return {
    code: payload.code || body?.code || error?.code || 'AUTH_REQUEST_FAILED',
    message: payload.message || payload.msg || body?.msg || error?.message || '认证请求失败',
    remainingAttempts: payload.remainingAttempts ?? payload.remaining_attempts,
    captchaRequired: Boolean(payload.captchaRequired ?? payload.captcha_required),
    captchaImage: payload.captchaImage || payload.captcha_image || '',
    challengeId: payload.challengeId || payload.challenge_id || '',
  }
}
