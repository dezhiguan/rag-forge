import { reactive, computed } from 'vue'
import { ELEVATION_KEY, ELEVATION_REASON_KEY } from '../utils/elevation-headers'

// Tab 级「提权（超管破玻璃）」状态。取代旧的全局「全平台视图」org 上下文：
//  - 超管默认只看自己组织；在支持提权的页面主动开启，才临时跨组织看全平台数据（留审计）。
//  - 提权按 Tab 局部生效、可随时关闭；刷新/重开页面默认回到未提权（不粘住）。
// request.js 拦截器读取 localStorage（见 resolveContextHeaders）注入 X-Admin-Override，服务端照常写破玻璃审计。

const state = reactive({ active: false, reason: '' })

// 模块加载即清残留：提权不跨刷新粘住。
try {
  localStorage.removeItem(ELEVATION_KEY)
  localStorage.removeItem(ELEVATION_REASON_KEY)
} catch {
  /* ignore */
}

export function useElevation() {
  /** 开启提权。理由必填（trim 后非空）；成功返回 true。 */
  function activate(reason) {
    const r = typeof reason === 'string' ? reason.trim() : ''
    if (!r) {
      return false
    }
    state.active = true
    state.reason = r.slice(0, 200)
    try {
      localStorage.setItem(ELEVATION_KEY, '1')
      localStorage.setItem(ELEVATION_REASON_KEY, state.reason)
    } catch {
      /* ignore */
    }
    return true
  }

  /** 关闭提权，回到当前组织口径。 */
  function deactivate() {
    state.active = false
    state.reason = ''
    try {
      localStorage.removeItem(ELEVATION_KEY)
      localStorage.removeItem(ELEVATION_REASON_KEY)
    } catch {
      /* ignore */
    }
  }

  return {
    active: computed(() => state.active),
    reason: computed(() => state.reason),
    activate,
    deactivate,
  }
}
