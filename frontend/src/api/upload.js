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
  if (error?.code) return error.code
  const body = error?.response?.data
  return body?.msg || body?.error || body?.code || 'NETWORK_ERROR'
}

export function uploadErrorMessage(error) {
  const code = uploadErrorCode(error)
  const messages = {
    KB_WRITE_FORBIDDEN: '您没有该 KB 的写权限',
    UPLOAD_NOT_FOUND: '上传未完成或已过期，请重新上传',
    SIZE_MISMATCH: '文件传输不完整，请重试',
    DOC_IDENTITY_CONFLICT: "已有相同内容文档，选择'覆盖'或'跳过'",
    OSS_PUT_FAILED: '上传失败，点击重试',
    NETWORK_ERROR: '上传失败，点击重试',
  }
  return messages[code] || '上传失败，点击重试'
}

const DEFAULT_CONTENT_TYPE = 'application/octet-stream'

export async function presignUpload(kbId, filename, contentType, declaredSize) {
  const res = await uploadRequest.post('/uploads/presign', {
    kbId,
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
  const res = await uploadRequest.post('/documents/register', {
    kbId,
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
