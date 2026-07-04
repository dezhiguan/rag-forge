import { reactive } from 'vue'

const state = reactive({ toasts: [] })
let nextId = 0

function push(type, message, opts = {}) {
  const text = String(message ?? '')
  // 去重：相同类型+文案的 toast 已在展示中则不再堆叠，避免多接口同时失败时的「toast 风暴」
  //（如质量看板 overview/by-kb/cost/worst-cases 同时网络失败会连弹 4 条同款并遮挡右上角控件）。
  const existing = state.toasts.find((t) => t.type === type && t.message === text)
  if (existing) return existing.id
  const id = ++nextId
  const duration = opts.duration ?? (type === 'error' ? 5000 : 3000)
  state.toasts.push({
    id,
    type,
    message: text,
    title: opts.title || '',
  })
  if (duration > 0) {
    setTimeout(() => dismiss(id), duration)
  }
  return id
}

export function dismiss(id) {
  state.toasts = state.toasts.filter((t) => t.id !== id)
}

export function useToast() {
  return {
    success: (msg, opts) => push('success', msg, opts),
    error: (msg, opts) => push('error', msg, opts),
    warning: (msg, opts) => push('warning', msg, opts),
    info: (msg, opts) => push('info', msg, opts),
    dismiss,
  }
}

export const toastState = state
