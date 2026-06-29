import axios from 'axios'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'
import { ERROR_MESSAGES } from './error-messages'

const toast = useToast()

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
  // 注入当前组织上下文（null = 个人，不带头）。从 localStorage 读，避免与 api 层循环依赖。
  try {
    const orgId = localStorage.getItem('ragforge.currentOrgId')
    if (orgId && orgId !== 'null' && orgId !== '') {
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
    if (!err.config?.silent) {
      const data = err.response?.data
      const status = err.response?.status
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
