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
  const code = payload.code || body?.code || payload.error || body?.error || error?.code || 'AUTH_REQUEST_FAILED'
  const rawMessage = payload.message || payload.msg || body?.msg || error?.message || ''
  return {
    code,
    message: friendlyAuthMessage(code, rawMessage),
    // HTTP 状态与原始请求配置随错误透传：session.js 据 status 区分
    // "会话真过期(401/403)"与"网络抖动"，据 config 做 401 续期后重放。
    status: error?.response?.status,
    config: error?.config,
    remainingAttempts: payload.remainingAttempts ?? payload.remaining_attempts,
    captchaRequired: Boolean(payload.captchaRequired ?? payload.captcha_required),
    captchaImage: payload.captchaImage || payload.captcha_image || '',
    challengeId: payload.challengeId || payload.challenge_id || '',
  }
}

function friendlyAuthMessage(code, message) {
  const text = `${code || ''} ${message || ''}`.toUpperCase()
  if (text.includes('SMS_SEND_TOO_FREQUENT')) return '验证码已发送，请稍后再试'
  if (
    text.includes('SMS_PHONE_DAY_LIMITED') ||
    text.includes('SMS_IP_MINUTE_LIMITED') ||
    text.includes('SMS_PROVIDER_RATE_LIMITED') ||
    text.includes('TOO MANY') ||
    text.includes('LIMIT')
  ) {
    return '验证码发送过于频繁，请稍后再试'
  }
  if (text.includes('SMS_LOGIN_NOT_REGISTERED')) return '该手机号尚未注册 RAGForge，请先点击「立即注册」'
  if (text.includes('SMS_CODE_INVALID')) return '验证码错误或已过期，请重新获取'
  if (text.includes('BAD_CREDENTIALS')) return '账号或密码不正确'
  if (text.includes('PLATFORM_ROLE_DENIED')) return '当前账号没有 RAGForge 管理权限，请联系管理员开通'
  if (text.includes('PASSWORD_WEAK')) return '密码至少需要 8 位'
  if (text.includes('PASSWORD_RESET_LOCKED')) return '操作过于频繁，请稍后再试'
  if (text.includes('SMS_PROVIDER') || text.includes('ALIYUN')) return '短信服务暂时不可用，请稍后再试'
  if (message && !/gateway/i.test(message)) return message
  return '认证请求失败，请稍后再试'
}
