import request from './request'
import { sha256 } from '../utils/file-hash'
import {
  UploadError,
  presignUpload,
  putToOss,
  registerUpload,
} from './upload'

const PRESIGN_THRESHOLD = 0
const DEFAULT_CONTENT_TYPE = 'application/octet-stream'

export const uploadDocument = (kbId, file, { onProgress, onPhaseChange, onConflict = 'REJECT' } = {}) => {
  if (PRESIGN_THRESHOLD > 0 && file.size <= PRESIGN_THRESHOLD) {
    return relayUpload(kbId, file, onProgress)
  }
  return presignUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict)
}

export const relayUpload = (kbId, file, onProgress) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append(
    'meta',
    JSON.stringify({
      kbId,
      identity: {},
      onConflict: 'REJECT',
      ingestSource: 'ui-upload-relay',
      metadata: { filename: file.name },
    }),
  )
  return request.post('/documents', formData, {
    onUploadProgress: (event) => {
      if (!event.total) return
      onProgress?.(Math.round((event.loaded / event.total) * 100))
    },
  })
}

async function presignUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict) {
  const contentType = file.type || DEFAULT_CONTENT_TYPE
  onPhaseChange?.('hashing')
  const contentMd5 = await sha256(file)

  onPhaseChange?.('presigning')
  const { uploadToken, presignedPutUrl } =
    await presignUpload(kbId, file.name, contentType, file.size)

  onPhaseChange?.('uploading')
  try {
    await putToOss(presignedPutUrl, file, contentType, onProgress)
  } catch (e) {
    throw new UploadError('OSS_PUT_FAILED', e)
  }

  onPhaseChange?.('registering')
  const result = await registerUpload(
    kbId,
    uploadToken,
    { contentMd5 },
    onConflict,
    'ui-upload',
    { filename: file.name },
  )
  onPhaseChange?.('done')
  return { data: result }
}

export const replaceDocument = (kbId, docId, file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/kb/${kbId}/documents/replace/${docId}`, formData)
}

export const listDocuments = (kbId, page = 1, size = 20) =>
  request.get(`/kb/${kbId}/documents`, { params: { page, size } })

export const getDocument = (id) => request.get(`/documents/${id}`)

export const listDocumentChunks = (id, page = 1, size = 20) =>
  request.get(`/documents/${id}/chunks`, { params: { page, size } })

export const getDocumentStatus = (id) => request.get(`/documents/${id}/status`)

export const deleteDocument = (id) => request.delete(`/documents/${id}`)

export const reprocessDocument = (id) => request.post(`/documents/${id}/reprocess`)

export const rechunkDocument = (id) => request.post(`/documents/${id}/rechunk`)

export const downloadDocument = (id) =>
  request.get(`/documents/${id}/download`, { responseType: 'blob' })
