// 分块策略代码 → 中文展示名。未知代码原样返回，避免把新策略显示成空。
const STRATEGY_LABELS = {
  MARKDOWN_HEADING: '按标题分块',
  RECURSIVE: '递归切分',
  FIXED_WINDOW: '固定窗口',
  SEMANTIC: '语义切分',
  TABLE_AWARE: '表格感知',
  SENTENCE: '句子切分',
  PARAGRAPH: '段落切分',
  IMAGE_PIPELINE: '图片管道',
}

export function chunkerStrategyLabel(code) {
  if (!code) return ''
  return STRATEGY_LABELS[code] || code
}
