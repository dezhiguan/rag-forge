export const PROCESSING_STATUSES = [
  'pending',
  'processing',
  'reprocessing',
  'parsing',
  'chunking',
  'embedding',
  'indexing',
]

export function normalizeDocStatus(parseStatus) {
  return parseStatus == null ? '' : String(parseStatus).trim().toLowerCase()
}

export function isProcessing(parseStatus) {
  return PROCESSING_STATUSES.includes(normalizeDocStatus(parseStatus))
}

export function isTerminal(parseStatus) {
  const status = normalizeDocStatus(parseStatus)
  return status === 'completed' || status === 'failed'
}

export function docStatusClass(parseStatus) {
  const status = normalizeDocStatus(parseStatus)
  if (status === 'completed') return 'badge-green'
  if (status === 'failed') return 'badge-red'
  if (isProcessing(parseStatus)) return 'badge-amber'
  return 'badge-gray'
}

export function docStatusLabel(parseStatus) {
  const map = {
    pending: '排队中',
    processing: '处理中',
    reprocessing: '重新处理中',
    parsing: '解析中',
    chunking: '分块中',
    embedding: '向量化',
    indexing: '索引中',
    completed: '已完成',
    failed: '失败',
  }
  if (isProcessing(parseStatus)) {
    return map[normalizeDocStatus(parseStatus)] || '处理中'
  }
  return map[normalizeDocStatus(parseStatus)] || parseStatus || '-'
}

export function summarizeContent(text, maxLen = 100) {
  if (!text) return ''
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLen) return normalized
  return `${normalized.slice(0, maxLen)}…`
}
