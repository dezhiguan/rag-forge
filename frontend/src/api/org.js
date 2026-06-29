import request from './request'

// 组织与成员管理 API（GitHub 式个人/组织权限）
export const listOrgs = () => request.get('/orgs')
export const createOrg = (data) => request.post('/orgs', data)
export const getOrg = (orgId) => request.get(`/orgs/${orgId}`)
export const listMembers = (orgId) => request.get(`/orgs/${orgId}/members`)
export const searchMemberCandidates = (orgId, q) =>
  request.get(`/orgs/${orgId}/member-candidates`, { params: { q } })
export const addMember = (orgId, data) => request.post(`/orgs/${orgId}/members`, data)
export const updateMember = (orgId, userId, data) =>
  request.patch(`/orgs/${orgId}/members/${userId}`, data)
export const removeMember = (orgId, userId) =>
  request.delete(`/orgs/${orgId}/members/${userId}`)

// 邀请（按手机号 + 接受流程）
export const inviteByPhone = (orgId, phone, role) =>
  request.post(`/orgs/${orgId}/invitations`, { phone, role })
export const listOrgInvitations = (orgId) => request.get(`/orgs/${orgId}/invitations`)
export const revokeInvitation = (orgId, invitationId) =>
  request.delete(`/orgs/${orgId}/invitations/${invitationId}`)
