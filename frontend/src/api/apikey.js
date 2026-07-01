import request from './request'

// 开发者中心 API key（按当前组织作用域；X-Org-Id 由 request 拦截器自动注入）
export const listApiKeys = () => request.get('/keys')
// payload 可为字符串(仅名称)或对象 { keyName, scopeMode, allowedKbIds, accessLevel, expiresAt }
export const createApiKey = (payload) =>
  request.post('/keys', typeof payload === 'string' ? { keyName: payload } : payload)
export const renameApiKey = (id, keyName) => request.patch(`/keys/${id}`, { keyName })
export const enableApiKey = (id, enabled) => request.put(`/keys/${id}/enable`, { enabled })
export const deleteApiKey = (id) => request.delete(`/keys/${id}`)
// 定向治理（超管破玻璃）：按名称/前缀查询 + 带原因吊销
export const governanceSearchKeys = (q) => request.get('/keys/governance', { params: { q } })
export const revokeApiKey = (id, reason) => request.post(`/keys/${id}/revoke`, { reason })
