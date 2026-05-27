export const PROCESSING_STATUSES = [
  'pending',
  'parsing',
  'chunking',
  'embedding',
  'indexing',
]

export function isProcessing(parseStatus) {
  return PROCESSING_STATUSES.includes(parseStatus)
}

export function isTerminal(parseStatus) {
  return parseStatus === 'completed' || parseStatus === 'failed'
}

export function docStatusClass(parseStatus) {
  if (parseStatus === 'completed') return 'badge-green'
  if (parseStatus === 'failed') return 'badge-red'
  if (isProcessing(parseStatus)) return 'badge-amber'
  return 'badge-gray'
}

export function docStatusLabel(parseStatus) {
  const map = {
    pending: '待处理',
    parsing: '解析中',
    chunking: '分块中',
    embedding: '向量化',
    indexing: '索引中',
    completed: '已完成',
    failed: '失败',
  }
  if (isProcessing(parseStatus)) {
    return map[parseStatus] || '处理中'
  }
  return map[parseStatus] || parseStatus || '-'
}

export function summarizeContent(text, maxLen = 100) {
  if (!text) return ''
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLen) return normalized
  return `${normalized.slice(0, maxLen)}…`
}
