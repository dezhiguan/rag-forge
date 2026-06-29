import request from './request'

// 站内通知
export const listNotifications = (unreadOnly = false) =>
  request.get('/notifications', { params: { unreadOnly } })
export const unreadCount = () => request.get('/notifications/unread-count')
export const markRead = (id) => request.post(`/notifications/${id}/read`)
export const markAllRead = () => request.post('/notifications/read-all')

// 我的组织邀请（被邀请方）
export const myInvitations = () => request.get('/invitations/mine')
export const acceptInvitation = (id) => request.post(`/invitations/${id}/accept`)
export const declineInvitation = (id) => request.post(`/invitations/${id}/decline`)
