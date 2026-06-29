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
  choices: [],            // 多选模式：[{ value, label, variant }]，留空则回退到 confirm/cancel 两按钮
  cancelValue: false,     // 取消（或遮罩点击 / ESC）时 resolve 的值；默认 false 兼容旧调用
  input: false,           // 输入(prompt)模式：确认时 resolve 输入字符串；取消 resolve cancelValue(默认 null)
  inputValue: '',
  inputPlaceholder: '',
  resolve: null,
})

export function confirm(opts = {}) {
  return new Promise((resolve) => {
    if (state.open && state.resolve) {
      state.resolve(state.cancelValue)
    }
    state.title = opts.title || '请确认'
    state.message = opts.message || ''
    state.detail = opts.detail || ''
    state.confirmText = opts.confirmText || '确认'
    state.cancelText = opts.cancelText || '取消'
    state.variant = opts.variant || 'default'
    state.icon = opts.icon || (opts.variant === 'danger' ? '⚠️' : opts.variant === 'warning' ? '❗' : '❓')
    state.choices = Array.isArray(opts.choices) ? opts.choices : []
    state.input = !!opts.input
    state.inputValue = opts.inputValue || ''
    state.inputPlaceholder = opts.inputPlaceholder || ''
    state.cancelValue = Object.prototype.hasOwnProperty.call(opts, 'cancelValue')
      ? opts.cancelValue
      : (state.choices.length > 0 || state.input ? null : false)
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
