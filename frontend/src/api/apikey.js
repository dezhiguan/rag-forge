import request from './request'

export const listApiKeys = () => request.get('/admin/api-keys')
export const createApiKey = (keyName) => request.post('/admin/api-keys', { keyName })
export const enableApiKey = (id, enabled) => request.put(`/admin/api-keys/${id}/enable`, { enabled })
export const deleteApiKey = (id) => request.delete(`/admin/api-keys/${id}`)
