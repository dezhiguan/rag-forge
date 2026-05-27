import request from './request'

export const search = (data) => request.post('/search', data)
