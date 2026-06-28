import request from './request'

// 组织与成员管理 API（GitHub 式个人/组织权限）
export const listOrgs = () => request.get('/orgs')
export const createOrg = (data) => request.post('/orgs', data)
export const getOrg = (orgId) => request.get(`/orgs/${orgId}`)
export const listMembers = (orgId) => request.get(`/orgs/${orgId}/members`)
export const addMember = (orgId, data) => request.post(`/orgs/${orgId}/members`, data)
export const updateMember = (orgId, userId, data) =>
  request.patch(`/orgs/${orgId}/members/${userId}`, data)
export const removeMember = (orgId, userId) =>
  request.delete(`/orgs/${orgId}/members/${userId}`)
