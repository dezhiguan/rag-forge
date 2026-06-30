export const ERROR_MESSAGES = {
  KB_ACCESS_DENIED: '无权访问该知识库或案例',
  KB_FILTER_DENIED: '无权访问该知识库或知识库不存在',
  KB_WRITE_FORBIDDEN: '您没有该知识库的写权限',
  SAMPLING_ADMIN_ONLY: '只有管理员可以修改抽样配置',
  SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM:
    '当前抽样率超过 10%，月度成本会显著增加，请勾选确认后再保存',
  JUDGE_RESULT_NOT_FOUND: '案例不存在或已被删除',
  CASE_ACCESS_DENIED: '无权访问该案例',
  EVAL_DATASET_NOT_FOUND: '数据集不存在',
  REPLAY_ALREADY_RUNNING: '已有回放任务正在进行，请稍后再试',
  DATASET_ID_REQUIRED: '请选择具体的数据集（管理员才能跑全量回放）',
  DATASET_KB_FORBIDDEN: '您没有访问该数据集对应知识库的权限',
  SERVER_ERROR: '服务暂时异常，请稍后重试',
  LOAD_FAILED: '数据加载失败，请刷新重试',
  NETWORK_ERROR: '网络连接失败，请检查网络后重试',
  INVALID_KB_ID: '请输入有效的知识库 ID（数字）',
  NOT_ORG_ADMIN: '只有组织的所有者或管理员才能执行此操作',
  NOT_ORG_OWNER: '只有组织所有者才能执行此操作',
  MODEL_TOGGLE_REQUIRES_PLATFORM_VIEW: '启停模型需切换到「全平台视图」（平台管理员）',
  KB_VISIBILITY_INVALID: '该可见性不适用于此知识库（团队库仅私有/组织可见，个人库仅私有/公开）',
  KB_VISIBILITY_PUBLIC_REASON_REQUIRED: '设为公开需填写原因（将记入审计）',
  KB_VISIBILITY_HAS_DEPENDENCIES: '该库被其他组织的密钥或评测引用，收紧前请确认影响',
  KB_VISIBILITY_REQUIRED: '请选择可见性',
}

export function translateErrorCode(code) {
  if (!code) return null
  return ERROR_MESSAGES[code] || null
}

export function translateErrorPayload(payload) {
  if (!payload) return ERROR_MESSAGES.LOAD_FAILED
  if (typeof payload === 'string') {
    return translateErrorCode(payload) || payload
  }
  const code = payload.msg || payload.error || payload.code
  if (typeof code === 'string' && ERROR_MESSAGES[code]) {
    return ERROR_MESSAGES[code]
  }
  if (payload.message && typeof payload.message === 'string') {
    return payload.message
  }
  if (typeof payload.code === 'number') {
    if (payload.code >= 500) return ERROR_MESSAGES.SERVER_ERROR
    if (payload.code === 404) return ERROR_MESSAGES.JUDGE_RESULT_NOT_FOUND
  }
  return typeof code === 'string' ? code : ERROR_MESSAGES.LOAD_FAILED
}

export function resolveHttpError(error, context = {}) {
  const status = error?.response?.status || error?.status
  const data = error?.response?.data
  const url = error?.config?.url || error?.response?.config?.url || ''

  if (status === 401) {
    return '登录已失效，请重新登录'
  }

  const translated = translateErrorPayload(data)
  if (translated && translated !== data?.msg) {
    return translated
  }

  if (status === 403) {
    if (context.kind === 'case') return ERROR_MESSAGES.CASE_ACCESS_DENIED
    if (context.kind === 'kb-filter') return ERROR_MESSAGES.KB_FILTER_DENIED
    if (url.includes('/evaluation/quality/sampling') || context.kind === 'sampling') {
      return ERROR_MESSAGES.SAMPLING_ADMIN_ONLY
    }
    if (url.includes('/evaluation/golden-set/replay') && context.datasetDenied) {
      return ERROR_MESSAGES.DATASET_KB_FORBIDDEN
    }
    return ERROR_MESSAGES.KB_ACCESS_DENIED
  }

  if (status === 404) {
    if (context.kind === 'case') return ERROR_MESSAGES.JUDGE_RESULT_NOT_FOUND
    return translateErrorPayload(data) || ERROR_MESSAGES.JUDGE_RESULT_NOT_FOUND
  }

  if (status === 409 && (data?.msg === 'REPLAY_ALREADY_RUNNING' || url.includes('/replay'))) {
    return ERROR_MESSAGES.REPLAY_ALREADY_RUNNING
  }

  if (status >= 500) {
    return context.load ? ERROR_MESSAGES.LOAD_FAILED : ERROR_MESSAGES.SERVER_ERROR
  }

  if (!error?.response) {
    return ERROR_MESSAGES.NETWORK_ERROR
  }

  return translateErrorPayload(data) || ERROR_MESSAGES.LOAD_FAILED
}

export function bottleneckLabel(value) {
  const key = String(value || '').toUpperCase()
  if (key === 'RETRIEVAL') return '检索瓶颈'
  if (key === 'GENERATION') return '生成瓶颈'
  if (key === 'BOTH') return '两者皆有'
  return value || '—'
}
