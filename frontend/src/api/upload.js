import axios from 'axios'
import { useAuth } from '../composables/useAuth'

const uploadRequest = axios.create({
  baseURL: '/api/v1',
  timeout: 0,
})

uploadRequest.interceptors.request.use((config) => {
  const { state } = useAuth()
  if (state.accessToken) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${state.accessToken}`
  }
  return config
})

export class UploadError extends Error {
  constructor(code, cause) {
    super(code)
    this.name = 'UploadError'
    this.code = code
    this.cause = cause
  }
}

export function uploadErrorCode(error) {
  // UploadError 由我们自己抛，code 是业务码（HASH_TIMEOUT / OSS_PUT_FAILED 等），直接用。
  if (error instanceof UploadError && error.code) return error.code
  // axios 把 response body 揭出来：后端返回 { code: 409, msg: "DOC_IDENTITY_CONFLICT", ... }，
  // 业务码在 msg 里；errorCode 字段是某些旧响应的别名，兼容保留。
  const body = error?.response?.data
  if (body) {
    if (typeof body.errorCode === 'string' && body.errorCode) return body.errorCode
    if (typeof body.msg === 'string' && body.msg) return body.msg
    if (typeof body.error === 'string' && body.error) return body.error
  }
  // 都拿不到时回退到 axios 自己的 error.code（ERR_NETWORK / ERR_BAD_REQUEST 等），
  // 比直接吐 "NETWORK_ERROR" 信息更密。
  if (error?.code) return error.code
  return 'NETWORK_ERROR'
}

export function uploadErrorMessage(error) {
  const code = uploadErrorCode(error)
  const messages = {
    INVALID_KB_ID: '知识库未选择或已失效，请重新选择',
    HASH_TIMEOUT: '文件指纹计算超时，请重试',
    HASH_WORKER_TIMEOUT: '文件指纹计算失败，请重试',
    KB_WRITE_FORBIDDEN: '您没有该知识库的写权限，无法上传到该知识库',
    UPLOAD_NOT_FOUND: '上传未完成或已过期，请重新上传',
    SIZE_MISMATCH: '文件传输不完整，请重试',
    DOC_IDENTITY_CONFLICT: "已有相同内容文档，选择'覆盖'或'跳过'",
    DOC_CONTENT_CHANGED_USE_REPLACE: '原文档内容已变更，请选择"覆盖"重新入库',
    OSS_PUT_FAILED: '上传失败，点击重试',
    NETWORK_ERROR: '上传失败，点击重试',
  }
  return messages[code] || '上传失败，点击重试'
}

const DEFAULT_CONTENT_TYPE = 'application/octet-stream'

function assertKbId(kbId) {
  const id = Number(kbId)
  if (!Number.isInteger(id) || id <= 0) {
    throw new UploadError('INVALID_KB_ID')
  }
  return id
}

export async function presignUpload(kbId, filename, contentType, declaredSize) {
  const id = assertKbId(kbId)
  const res = await uploadRequest.post('/uploads/presign', {
    kbId: id,
    filename,
    contentType,
    declaredSize,
  })
  return unwrap(res)
}

export async function registerUpload(
  kbId,
  uploadToken,
  identity,
  onConflict = 'REJECT',
  ingestSource = 'ui-upload',
  metadata = {},
) {
  const id = assertKbId(kbId)
  const res = await uploadRequest.post('/documents/register', {
    kbId: id,
    uploadToken,
    identity,
    onConflict,
    ingestSource,
    metadata,
  })
  return unwrap(res)
}

export function putToOss(presignedPutUrl, file, contentType, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const realContentType = contentType || DEFAULT_CONTENT_TYPE
    xhr.open('PUT', presignedPutUrl)
    xhr.setRequestHeader('Content-Type', realContentType)

    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || !onProgress) return
      onProgress(Math.round((event.loaded / event.total) * 100))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(100)
        resolve()
        return
      }
      reject(new UploadError('OSS_PUT_FAILED', { status: xhr.status, responseText: xhr.responseText }))
    }
    xhr.onerror = () => reject(new UploadError('OSS_PUT_FAILED'))
    xhr.ontimeout = () => reject(new UploadError('OSS_PUT_FAILED'))
    xhr.send(file)
  })
}

function unwrap(res) {
  const body = res.data
  if (body && typeof body.code === 'number') {
    if (body.code !== 200) {
      throw new UploadError(body.msg || body.error || String(body.code), body)
    }
    return body.data
  }
  return body
}
