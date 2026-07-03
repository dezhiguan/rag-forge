import request from './request'
import { sha256 } from '../utils/file-hash'
import {
  UploadError,
  presignUpload,
  putToOss,
  registerUpload,
} from './upload'

const PRESIGN_THRESHOLD = 0 // 0 = 全部走直传，relayUpload 仅用于直传失败后的临时兜底或后续小文件优化
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
  csv: 'text/csv',
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

function sniffContentTypeFromBytes(head) {
  if (!head || head.length < 4) return null
  if (head[0] === 0x89 && head[1] === 0x50 && head[2] === 0x4e && head[3] === 0x47) return 'image/png'
  if (head[0] === 0xff && head[1] === 0xd8) return 'image/jpeg'
  if (head[0] === 0x47 && head[1] === 0x49 && head[2] === 0x46) return 'image/gif'
  if (head.length >= 12 && head[0] === 0x52 && head[1] === 0x49 && head[2] === 0x46 && head[3] === 0x46) {
    return 'image/webp'
  }
  return null
}

export async function inferContentTypeAsync(file) {
  const fromName = inferContentType(file)
  if (fromName !== DEFAULT_CONTENT_TYPE) return fromName
  if (file?.type) return file.type
  try {
    const head = new Uint8Array(await file.slice(0, 12).arrayBuffer())
    return sniffContentTypeFromBytes(head) || fromName
  } catch {
    return fromName
  }
}

const DOWNLOAD_PATHS = [
  (id) => `/documents/${id}/download`,
  (id) => `/documents/${id}/file`,
  (id) => `/documents/${id}/download/file`,
  (id) => `/documents/${id}/content`,
]

function assertKbId(kbId) {
  const id = Number(kbId)
  if (!Number.isInteger(id) || id <= 0) {
    throw new Error('INVALID_KB_ID')
  }
  return id
}

export const uploadDocument = (kbId, file, { onProgress, onPhaseChange, onConflict = 'REJECT' } = {}) => {
  const id = assertKbId(kbId)
  // 统一走直传：图片也通过 presign 直传 OSS
  if (PRESIGN_THRESHOLD > 0 && file.size <= PRESIGN_THRESHOLD) {
    return relayUploadFlow(id, file, onProgress, onPhaseChange, onConflict)
  }
  return presignUploadFlow(id, file, onProgress, onPhaseChange, onConflict)
}

export const relayUpload = (kbId, file, onProgress, onConflict = 'REJECT') => {
  const id = assertKbId(kbId)
  const formData = new FormData()
  formData.append('file', file)
  formData.append(
    'meta',
    JSON.stringify({
      kbId: id,
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
  const contentType = (await inferContentTypeAsync(file)) || DEFAULT_CONTENT_TYPE
  onPhaseChange?.('hashing')
  // hashing 阶段加 60s 上限，避免 Web Worker 在极端环境（Playwright 有头模式 / 旧浏览器）真挂死时无声等待。
  // 30MB 实测 ~300ms，60s 已经远高于任何合理上限。
  const contentMd5 = await Promise.race([
    sha256(file),
    new Promise((_, rej) => setTimeout(() => rej(new UploadError('HASH_TIMEOUT')), 60_000)),
  ])

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
  return normalizeUploadResult(result)
}

export const replaceDocument = (kbId, docId, file) => {
  const id = assertKbId(kbId)
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/kb/${id}/documents/replace/${docId}`, formData)
}

// flatten=true：文档列表层，平铺子文档+独立文档（排除压缩包容器），子文档带 sourceArchiveName 来源标识。
// flatten=false（默认）：知识库列表层，压缩包容器+独立文档。
export const listDocuments = (kbId, page = 1, size = 20, keyword, flatten = false) => {
  const id = assertKbId(kbId)
  return request.get(`/kb/${id}/documents`, {
    params: { page, size, keyword: keyword || undefined, flatten: flatten || undefined },
  })
}

export const getDocument = (id) => request.get(`/documents/${id}`)

// 压缩包容器下钻：拉取该容器展开出的子文档列表（DocumentVO[]）。
// 契约：GET /api/v1/documents/{containerId}/children，由后端并行实现。
export const listDocumentChildren = (containerId) => {
  const id = Number(containerId)
  if (!Number.isInteger(id) || id <= 0) {
    return Promise.reject(new Error('INVALID_CONTAINER_ID'))
  }
  return request.get(`/documents/${id}/children`)
}

export const listDocumentChunks = (id, page = 1, size = 20) =>
  request.get(`/documents/${id}/chunks`, { params: { page, size } })

export const getDocumentStatus = (id) => request.get(`/documents/${id}/status`)

export const deleteDocument = (id) => request.delete(`/documents/${id}`)

export const reprocessDocument = (id) => request.post(`/documents/${id}/reprocess`)

export const rechunkDocument = (id, options = {}) =>
  request.post(`/documents/${id}/rechunk`, {
    strategy: options.strategy,
    chunkSize: options.chunkSize,
    chunkOverlap: options.chunkOverlap,
  })

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
