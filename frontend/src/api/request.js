import axios from 'axios'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'
import { ERROR_MESSAGES } from './error-messages'
import { refreshAccessToken } from './auth'

const toast = useToast()

// 静默续期(单飞):access token 短时到期时,用 refresh cookie 悄悄换新 token，
// 多个并发 401 共享同一次 /refresh，避免重复刷新与重复"登录已过期"提示。
let refreshPromise = null
function silentRefresh() {
  if (!refreshPromise) {
    refreshPromise = refreshAccessToken()
      .then((session) => {
        useAuth().setSession(session.accessToken, session.user)
        return session.accessToken
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

const ERROR_CODE_LABELS = {
  KB_ACCESS_DENIED: '无权访问该知识库或案例',
  KB_WRITE_FORBIDDEN: '您没有该知识库的写权限',
  ANSWER_DISABLED: '该知识库没有开启应答模式，请先到知识库开启应答模式',
  QUERY_REQUIRED: '请输入您的问题',
  KB_IDS_REQUIRED: '请至少选择一个知识库',
  PII_LEAK: '答案中检测到敏感信息，已停止显示',
  JUDGE_RESULT_NOT_FOUND: '案例不存在或已被删除',
  EVAL_DATASET_NOT_FOUND: '数据集不存在',
  REPLAY_ALREADY_RUNNING: '已有回放任务正在进行，请稍后再试',
  SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM: '当前抽样率超过 10%，月度成本会显著增加，请勾选确认后再保存',
  SAMPLING_ADMIN_ONLY: '只有管理员可以修改抽样配置',
  DATASET_ID_REQUIRED: '请选择具体的数据集（管理员才能跑全量回放）',
  UPLOAD_NOT_FOUND: '上传未完成或已过期，请重新上传',
  SIZE_MISMATCH: '文件传输不完整，请重试',
  DOC_IDENTITY_CONFLICT: '已有相同内容文档，请选择覆盖或跳过',
  FILE_TOO_LARGE_FOR_RELAY: '文件过大，请改用直传上传',
  IMAGE_CONTENT_NOT_SUPPORTED_UNTIL_T10: '当前版本不支持该格式的图片',
  UNSUPPORTED_CONTENT_TYPE: '暂不支持此文件类型',
  SEMANTIC_REQUIRES_LONG_TEXT: '文本不足 2000 字，无法使用语义分块',
  INVALID_STRATEGY: '选择的策略无效，请刷新页面重试',
  CHUNK_SIZE_OUT_OF_RANGE: '块大小需要在 256-2048 范围内',
  CHUNK_OVERLAP_OUT_OF_RANGE: '块重叠需要在 0-512 范围内',
  ALREADY_IN_PROGRESS: '文档正在处理，请等待完成后重试',
  ENABLED_REQUIRED: '缺少启用状态参数，请刷新页面后重试',
  NOT_ORG_ADMIN: '只有组织的所有者或管理员才能执行此操作',
  NOT_ORG_OWNER: '只有组织所有者才能执行此操作',
  MODEL_TOGGLE_REQUIRES_PLATFORM_VIEW: '启停模型需切换到「全平台视图」（平台管理员）',
  KB_VISIBILITY_INVALID: '该可见性不适用于此知识库（团队库仅私有/组织可见，个人库仅私有/公开）',
  KB_VISIBILITY_PUBLIC_REASON_REQUIRED: '设为公开需填写原因（将记入审计）',
  KB_VISIBILITY_HAS_DEPENDENCIES: '该库被其他组织的密钥或评测引用，收紧前请确认影响',
  KB_VISIBILITY_REQUIRED: '请选择可见性',
  INDIVIDUAL_ORG_NO_INVITE: '个人组织不支持邀请成员',
  ALREADY_MEMBER: '该手机号对应的用户已是本组织成员，无需重复邀请',
  INVITE_ALREADY_PENDING: '该用户已有一条待接受的邀请，请等待对方处理',
  INVITE_NOT_FOUND: '邀请不存在或已被撤销',
  INVITE_NOT_PENDING: '该邀请已被处理，无法重复操作',
  INVITE_EXPIRED: '邀请已过期，请重新发送',
  MODEL_DISABLE_WOULD_LEAVE_PURPOSE_UNAVAILABLE: '停用后该用途将没有可用模型，请先启用备用模型',
  Forbidden: '您没有执行该操作的权限',
  Unauthorized: '登录已过期，请重新登录',
}

// 模型 & 成本中心：用途环节中文名（与前端 PURPOSE_META 保持一致）
const PURPOSE_LABELS = {
  EMBEDDING: '向量化',
  JUDGE: '评测/Judge',
  REWRITE: 'Query 改写',
  ANSWER: '答案生成',
  RERANK: '精排',
  OCR: '图片 OCR',
}

// 带动态后缀的错误码翻译（形如 CODE:DETAIL）。返回 null 表示不匹配。
function translateDynamicCode(code) {
  if (typeof code !== 'string') return null
  const idx = code.indexOf(':')
  if (idx < 0) return null
  const prefix = code.slice(0, idx)
  const detail = code.slice(idx + 1)
  if (prefix === 'MODEL_DISABLE_WOULD_LEAVE_PURPOSE_UNAVAILABLE') {
    const purpose = PURPOSE_LABELS[detail] || detail
    return `停用失败：「${purpose}」环节将没有可用模型，请先为该用途启用一个备用模型`
  }
  if (prefix === 'MODEL_NOT_FOUND') {
    return `模型不存在或已下线：${detail}`
  }
  return null
}

export function translateError(payload) {
  if (!payload) return '请求失败，请稍后重试'
  if (typeof payload === 'string') {
    return translateDynamicCode(payload) || ERROR_CODE_LABELS[payload] || payload
  }
  const code = payload.msg || payload.error || payload.code
  const dynamic = translateDynamicCode(code)
  if (dynamic) return dynamic
  if (code && ERROR_CODE_LABELS[code]) return ERROR_CODE_LABELS[code]
  if (payload.message) return payload.message
  if (typeof payload.code === 'number') {
    if (payload.code === 500) return '服务暂时异常，请稍后重试'
    if (payload.code === 502 || payload.code === 503 || payload.code === 504)
      return '后台服务繁忙，请稍后重试'
  }
  return code || '请求失败，请稍后重试'
}

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 0,
})

request.interceptors.request.use((config) => {
  const { state } = useAuth()
  config.headers = config.headers || {}
  if (state.accessToken) {
    config.headers.Authorization = `Bearer ${state.accessToken}`
  }
  // 注入当前组织上下文。从 localStorage 读，避免与 api 层循环依赖。
  // platform = 超管全平台视图 → 破玻璃头(X-Admin-Override)；其余 = X-Org-Id；个人(null)不带头。
  try {
    const orgId = localStorage.getItem('ragforge.currentOrgId')
    if (orgId === 'platform') {
      config.headers['X-Admin-Override'] = 'true'
      config.headers['X-Admin-Override-Reason'] = 'platform-dashboard-view'
    } else if (orgId && orgId !== 'null' && orgId !== '') {
      config.headers['X-Org-Id'] = orgId
    }
  } catch {
    /* ignore */
  }
  return config
})

request.interceptors.response.use(
  (res) => {
    if (res.config?.responseType === 'blob') {
      return res
    }
    const body = res.data
    if (body && typeof body.code === 'number' && body.code !== 200) {
      if (!res.config?.silent) {
        toast.error(translateError(body))
      }
      return Promise.reject(body)
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const original = err.config || {}
    // 401：先尝试静默续期并重放原请求；每个请求只重试一次，避免死循环。
    if (status === 401 && !original._retried) {
      original._retried = true
      return silentRefresh()
        .then(() => request(original)) // 重放（请求拦截器会带上新 token）
        .catch(() => {
          // 续期失败 = 会话真过期：清会话、提示、跳登录（登录页支持 reason=expired 回跳）
          useAuth().clearSession()
          if (!original.silent) {
            toast.error('登录已过期，请重新登录')
          }
          if (typeof window !== 'undefined' && !/\/login/.test(window.location.pathname)) {
            const here = window.location.pathname + window.location.search
            window.location.href = `/login?reason=expired&redirect=${encodeURIComponent(here)}`
          }
          return Promise.reject(err)
        })
    }
    if (!err.config?.silent) {
      const data = err.response?.data
      let msg
      if (status === 401) {
        msg = '登录已过期，请重新登录'
      } else if (status === 403) {
        const url = err.config?.url || ''
        if (url.includes('/evaluation/quality/sampling')) {
          msg = ERROR_MESSAGES.SAMPLING_ADMIN_ONLY
        } else {
          msg = translateError(data) || ERROR_MESSAGES.KB_ACCESS_DENIED
        }
      } else if (status === 404) {
        msg = translateError(data) || '资源不存在'
      } else if (err.code === 'ECONNABORTED' || /timeout/i.test(err.message || '')) {
        msg = '请求超时，请检查网络后重试'
      } else if (!err.response) {
        msg = '网络连接失败，请检查网络后重试'
      } else {
        msg = translateError(data) || '请求失败，请稍后重试'
      }
      toast.error(msg)
    }
    return Promise.reject(err)
  }
)

export default request
