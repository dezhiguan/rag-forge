// 纯函数：根据本地存储状态，计算一次请求应注入的组织上下文 / 提权（破玻璃）头。
// 抽成无副作用纯函数便于单测；request.js 拦截器调用它，useElevation 负责写状态。
//
// 规则：
//  - 处于「提权」态（ragforge.elevation 有值）→ 注入 X-Admin-Override + 理由头（跨组织看全平台，留审计）。
//  - 否则按当前组织 → 注入 X-Org-Id（个人/未选择/历史 platform 值一律不带组织头）。
// HTTP 头仅允许 ISO-8859-1，中文理由须 encodeURIComponent 后传输，后端 URLDecoder 还原。

export const ELEVATION_KEY = 'ragforge.elevation'
export const ELEVATION_REASON_KEY = 'ragforge.adminOverrideReason'
export const CURRENT_ORG_KEY = 'ragforge.currentOrgId'

/**
 * @param {{getItem:(k:string)=>(string|null)}} store 形如 localStorage 的读取器
 * @returns {Object} 需要注入的请求头
 */
export function resolveContextHeaders(store) {
  const headers = {}
  if (!store || typeof store.getItem !== 'function') {
    return headers
  }
  const elevated = store.getItem(ELEVATION_KEY)
  if (elevated) {
    headers['X-Admin-Override'] = 'true'
    const reason = store.getItem(ELEVATION_REASON_KEY) || 'platform-view'
    headers['X-Admin-Override-Reason'] = encodeURIComponent(reason)
    return headers
  }
  const orgId = store.getItem(CURRENT_ORG_KEY)
  if (orgId && orgId !== 'null' && orgId !== '' && orgId !== 'platform') {
    headers['X-Org-Id'] = orgId
  }
  return headers
}
