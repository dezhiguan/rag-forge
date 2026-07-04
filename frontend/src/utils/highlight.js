// 把 text 按 keyword（大小写不敏感）切成 [{ t, hit }] 片段，供 <mark> 渲染高亮命中部分。
// 用模板拼接而非 v-html，避免文本里的尖括号/脚本被当 HTML 注入。
// keyword 为空、或形如 #数字（编号定位）时整段返回、不高亮。
export function highlightParts(text, keyword) {
  const s = String(text ?? '')
  const kw = (keyword || '').trim()
  if (!kw || /^#\d+$/.test(kw)) return [{ t: s, hit: false }]
  const parts = []
  const lc = s.toLowerCase()
  const lq = kw.toLowerCase()
  let i = 0
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const j = lc.indexOf(lq, i)
    if (j < 0) {
      parts.push({ t: s.slice(i), hit: false })
      break
    }
    if (j > i) parts.push({ t: s.slice(i, j), hit: false })
    parts.push({ t: s.slice(j, j + kw.length), hit: true })
    i = j + kw.length
  }
  return parts
}
