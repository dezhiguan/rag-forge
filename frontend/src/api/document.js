import request from './request'
import { sha256 } from '../utils/file-hash'
import {
  presignUpload,
  putToOss,
  registerUpload,
} from './upload'

const PRESIGN_THRESHOLD = 0
const DEFAULT_CONTENT_TYPE = 'application/octet-stream'

const EXTENSION_CONTENT_TYPES = {
  pdf: 'application/pdf',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  md: 'text/markdown',
  markdown: 'text/markdown',
  html: 'text/html',
  htm: 'text/html',
  txt: 'text/plain',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
}

export function inferContentType(file) {
  if (file?.type) return file.type
  const name = file?.name || ''
  const idx = name.lastIndexOf('.')
  if (idx < 0) return DEFAULT_CONTENT_TYPE
  const ext = name.slice(idx + 1).toLowerCase()
  return EXTENSION_CONTENT_TYPES[ext] || DEFAULT_CONTENT_TYPE
}

function isImageFile(file) {
  return inferContentType(file).startsWith('image/')
}

const DOWNLOAD_PATHS = [
  (id) => `/documents/${id}/download`,
  (id) => `/documents/${id}/file`,
  (id) => `/documents/${id}/download/file`,
  (id) => `/documents/${id}/content`,
]

async function requestDownloadDocument(path) {
  try {
    return await request.get(path, { responseType: 'blob', silent: true })
  } catch (err) {
    const status = err?.response?.status
    const isNotFound = status === 404
    if (isNotFound && path !== path) {
      throw err
    }
    if (!isNotFound) {
      err.config = err.config || {}
      err.config.silent = false
      throw err
    }
    throw err
  }
}

export const uploadDocument = (kbId, file, { onProgress, onPhaseChange, onConflict = 'REJECT' } = {}) => {
  // Images go through server relay: browser PUT to OSS often fails (CORS / presign).
  if (isImageFile(file)) {
    return relayUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict)
  }
  if (PRESIGN_THRESHOLD > 0 && file.size <= PRESIGN_THRESHOLD) {
    return relayUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict)
  }
  return presignUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict)
}

export const relayUpload = (kbId, file, onProgress, onConflict = 'REJECT') => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append(
    'meta',
    JSON.stringify({
      kbId,
      identity: {},
      onConflict,
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

function normalizeUploadResult(result) {
  return { data: result?.data ?? result }
}

async function relayUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict) {
  onPhaseChange?.('relay')
  onProgress?.(5)
  const result = await relayUpload(kbId, file, (progress) => {
    onProgress?.(Math.max(5, progress))
  }, onConflict)
  onPhaseChange?.('done')
  onProgress?.(100)
  return normalizeUploadResult(result)
}

async function presignUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict) {
  const contentType = inferContentType(file)
  onPhaseChange?.('hashing')
  const contentMd5 = await sha256(file)

  onPhaseChange?.('presigning')
  const { uploadToken, presignedPutUrl } =
    await presignUpload(kbId, file.name, contentType, file.size)

  onPhaseChange?.('uploading')
  try {
    await putToOss(presignedPutUrl, file, contentType, onProgress)
  } catch {
    return relayUploadFlow(kbId, file, onProgress, onPhaseChange, onConflict)
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
  return normalizeUploadResult(result)
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

export const downloadDocument = async (id) => {
  const docId = Number(id)
  if (Number.isNaN(docId)) {
    return request.get(`/documents/${id}/download`, { responseType: 'blob' })
  }

  let lastErr = null
  for (let index = 0; index < DOWNLOAD_PATHS.length; index += 1) {
    const path = DOWNLOAD_PATHS[index](docId)
    try {
      return await request.get(path, { responseType: 'blob', silent: true })
    } catch (err) {
      lastErr = err
      const status = err?.response?.status
      if (status !== 404) {
        err.config = err.config || {}
        err.config.silent = false
        throw err
      }
      if (index === DOWNLOAD_PATHS.length - 1) {
        if (lastErr?.config) lastErr.config.silent = false
        throw lastErr
      }
    }
  }
}
