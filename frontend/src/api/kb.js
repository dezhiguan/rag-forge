import request from './request'

// 普通调用只列"自有 + public + acl"。adminOverrideReason 非空时附带破玻璃头，
// 让平台 ADMIN 临时查看全部知识库（后端会写审计）。
export const listKb = (adminOverrideReason) =>
  request.get('/kb', adminOverrideReason
    ? { headers: { 'X-Admin-Override': 'true', 'X-Admin-Override-Reason': adminOverrideReason } }
    : undefined)
export const createKb = (data) => request.post('/kb', data)
export const getKb = (id) => request.get(`/kb/${id}`)
export const updateKb = (id, data) => request.put(`/kb/${id}`, data)
export const deleteKb = (id) => request.delete(`/kb/${id}`)
