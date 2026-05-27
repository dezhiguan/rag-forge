import request from './request'

export const uploadDocument = (kbId, file) => {
  const formData = new FormData()
  formData.append('file', file)
  // Let axios set multipart boundary automatically.
  return request.post(`/kb/${kbId}/documents`, formData)
}

export const replaceDocument = (kbId, docId, file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/kb/${kbId}/documents/replace/${docId}`, formData)
}

export const listDocuments = (kbId, page = 1, size = 20) =>
  request.get(`/kb/${kbId}/documents`, { params: { page, size } })

export const getDocument = (id) => request.get(`/documents/${id}`)

export const getDocumentStatus = (id) => request.get(`/documents/${id}/status`)

export const deleteDocument = (id) => request.delete(`/documents/${id}`)

export const reprocessDocument = (id) => request.post(`/documents/${id}/reprocess`)

export const downloadDocument = (id) =>
  request.get(`/documents/${id}/download`, { responseType: 'blob' })

