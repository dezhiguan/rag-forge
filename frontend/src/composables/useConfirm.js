import { reactive } from 'vue'

const state = reactive({
  open: false,
  title: '',
  message: '',
  detail: '',
  confirmText: '确认',
  cancelText: '取消',
  variant: 'default', // 'default' | 'danger' | 'warning'
  icon: '',
  resolve: null,
})

export function confirm(opts = {}) {
  return new Promise((resolve) => {
    if (state.open && state.resolve) {
      state.resolve(false)
    }
    state.title = opts.title || '请确认'
    state.message = opts.message || ''
    state.detail = opts.detail || ''
    state.confirmText = opts.confirmText || '确认'
    state.cancelText = opts.cancelText || '取消'
    state.variant = opts.variant || 'default'
    state.icon = opts.icon || (opts.variant === 'danger' ? '⚠️' : opts.variant === 'warning' ? '❗' : '❓')
    state.resolve = resolve
    state.open = true
  })
}

export function resolveConfirm(result) {
  if (!state.open) return
  state.open = false
  const fn = state.resolve
  state.resolve = null
  fn?.(result)
}

export const confirmState = state
