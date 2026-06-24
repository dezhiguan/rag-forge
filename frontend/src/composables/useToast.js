import { reactive } from 'vue'

const state = reactive({ toasts: [] })
let nextId = 0

function push(type, message, opts = {}) {
  const id = ++nextId
  const duration = opts.duration ?? (type === 'error' ? 5000 : 3000)
  state.toasts.push({
    id,
    type,
    message: String(message ?? ''),
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
