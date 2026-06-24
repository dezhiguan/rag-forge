/** 与后端 RechunkSupport / request.js 错误码保持一致 */
export const CHUNK_SIZE_MIN = 256
export const CHUNK_SIZE_MAX = 2048
export const CHUNK_OVERLAP_MIN = 0
export const CHUNK_OVERLAP_MAX = 512

/** 块大小下限 256：保证单块有足够上下文供 embedding 召回 */
export function blockNegativeNumberKey(e) {
  if (e.key === '-' || e.key === '+' || e.key === 'e' || e.key === 'E') {
    e.preventDefault()
  }
}

export function normalizeChunkSize(value) {
  if (value === '' || value == null || Number.isNaN(value)) return null
  const n = Math.trunc(Number(value))
  if (!Number.isFinite(n)) return null
  if (n < CHUNK_SIZE_MIN) return CHUNK_SIZE_MIN
  if (n > CHUNK_SIZE_MAX) return CHUNK_SIZE_MAX
  return n
}

export function normalizeChunkOverlap(value) {
  if (value === '' || value == null || Number.isNaN(value)) return null
  const n = Math.trunc(Number(value))
  if (!Number.isFinite(n)) return null
  if (n < CHUNK_OVERLAP_MIN) return CHUNK_OVERLAP_MIN
  if (n > CHUNK_OVERLAP_MAX) return CHUNK_OVERLAP_MAX
  return n
}

export function chunkSizeError(value) {
  if (value == null || value === '' || Number.isNaN(value)) return null
  const n = Number(value)
  if (!Number.isFinite(n)) return '请输入有效数字'
  if (n < CHUNK_SIZE_MIN || n > CHUNK_SIZE_MAX) {
    return `块大小需要在 ${CHUNK_SIZE_MIN}-${CHUNK_SIZE_MAX} 范围内`
  }
  return null
}

export function chunkOverlapError(value, chunkSize) {
  if (value == null || value === '' || Number.isNaN(value)) return null
  const n = Number(value)
  if (!Number.isFinite(n)) return '请输入有效数字'
  if (n < CHUNK_OVERLAP_MIN || n > CHUNK_OVERLAP_MAX) {
    return `块重叠需要在 ${CHUNK_OVERLAP_MIN}-${CHUNK_OVERLAP_MAX} 范围内`
  }
  const size = Number(chunkSize)
  if (Number.isFinite(size) && size >= CHUNK_SIZE_MIN && n >= size) {
    return '块重叠必须小于块大小'
  }
  return null
}

export function isChunkParamsValid(chunkSize, chunkOverlap) {
  return !chunkSizeError(chunkSize) && !chunkOverlapError(chunkOverlap, chunkSize)
}
