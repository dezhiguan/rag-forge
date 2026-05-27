import request from './request'

export const listKb = () => request.get('/kb')
export const createKb = (data) => request.post('/kb', data)
export const getKb = (id) => request.get(`/kb/${id}`)
export const updateKb = (id, data) => request.put(`/kb/${id}`, data)
export const deleteKb = (id) => request.delete(`/kb/${id}`)
