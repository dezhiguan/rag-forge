// 平台基准库·v1 加载器（幂等）：建库→上传全格式语料→等入库→建数据集→批量导入100题(judge_enabled+is_core)→触发回放
// 运行前提：is_core 列(V57)已部署。用法：node load_golden.mjs [--replay]
import fs from 'node:fs'
import path from 'node:path'

const BASE = 'https://ragforge.net'
const ACCOUNT = '18565040934', PW = '03180934xX.'
const ORG = '0'                          // 系统组织
const KB_NAME = '平台基准库·v1'
const DS_NAME = '平台基准集·v1'
const CORPUS = path.join(path.dirname(new URL(import.meta.url).pathname), 'corpus')
const QFILE = path.join(path.dirname(new URL(import.meta.url).pathname), 'golden_questions.json')
const DO_REPLAY = process.argv.includes('--replay')

let TOKEN = ''
const H = (extra = {}) => ({ Authorization: `Bearer ${TOKEN}`, 'X-Org-Id': ORG, ...extra })
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

async function j(method, url, { headers = {}, body, raw } = {}) {
  const res = await fetch(BASE + url, { method, headers, body: raw ? body : (body ? JSON.stringify(body) : undefined) })
  const txt = await res.text()
  let d; try { d = JSON.parse(txt) } catch { d = { _raw: txt } }
  return { status: res.status, d }
}

async function login() {
  const { d } = await j('POST', '/api/auth/login', { headers: { 'Content-Type': 'application/json' }, body: { account: ACCOUNT, password: PW, remember: false } })
  TOKEN = d?.data?.accessToken
  if (!TOKEN) throw new Error('登录失败: ' + JSON.stringify(d).slice(0, 200))
  console.log('✅ 登录成功 (超管)')
}

async function ensureKb() {
  const { d } = await j('GET', '/api/v1/kb?page=1&size=200', { headers: H() })
  const list = Array.isArray(d?.data) ? d.data : (d?.data?.records || [])
  let kb = list.find(k => k.name === KB_NAME)
  if (kb) { console.log(`ℹ️ 基准库已存在 id=${kb.id}`); return kb.id }
  const r = await j('POST', '/api/v1/kb', { headers: H({ 'Content-Type': 'application/json' }),
    body: { name: KB_NAME, description: '平台级黄金集检索质量基准语料（全格式，RAGForge 平台技术知识，冻结基线）', imageProcessingMode: 'ON', orgId: 0, visibility: undefined } })
  if (!r.d?.data?.id) throw new Error('建库失败: ' + JSON.stringify(r.d).slice(0, 300))
  console.log(`✅ 新建基准库 id=${r.d.data.id} (imageProcessingMode=ON)`)
  return r.d.data.id
}

const MIME = { '.md': 'text/markdown', '.txt': 'text/plain', '.html': 'text/html', '.csv': 'text/csv',
  '.pdf': 'application/pdf', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp' }

async function uploadCorpus(kbId) {
  // 已有文档则跳过（幂等）
  const { d: docs } = await j('GET', `/api/v1/kb/${kbId}/documents?page=1&size=200`, { headers: H() })
  const existing = new Set((docs?.data?.records || docs?.data?.list || docs?.data || []).map(x => x.filename || x.name))
  const files = fs.readdirSync(CORPUS).filter(f => !f.startsWith('.')).sort()
  let uploaded = 0
  for (const f of files) {
    if (existing.has(f)) { console.log(`  ↷ 已存在，跳过 ${f}`); continue }
    const buf = fs.readFileSync(path.join(CORPUS, f))
    const ext = path.extname(f).toLowerCase()
    const fd = new FormData()
    fd.append('kbId', String(kbId))
    fd.append('file', new Blob([buf], { type: MIME[ext] || 'application/octet-stream' }), f)
    const res = await fetch(BASE + '/api/v1/documents/upload', { method: 'POST', headers: H(), body: fd })
    const t = await res.text()
    console.log(`  ⬆️ ${f} → http=${res.status} ${t.slice(0, 80)}`)
    if (res.status === 200) uploaded++
    await sleep(400)
  }
  console.log(`✅ 上传完成，新上传 ${uploaded}/${files.length}`)
}

async function waitIngest(kbId) {
  for (let i = 0; i < 40; i++) {
    const { d } = await j('GET', `/api/v1/kb/${kbId}/documents?page=1&size=200`, { headers: H() })
    const recs = d?.data?.records || d?.data?.list || d?.data || []
    const st = recs.reduce((m, x) => { const s = (x.status || '').toUpperCase(); m[s] = (m[s] || 0) + 1; return m }, {})
    const done = recs.filter(x => ['COMPLETED', 'ACTIVE', 'SUCCESS', 'DONE'].includes((x.status || '').toUpperCase())).length
    console.log(`  入库进度: ${done}/${recs.length} ${JSON.stringify(st)}`)
    if (recs.length > 0 && done >= recs.length) { console.log('✅ 全部入库完成'); return }
    await sleep(15000)
  }
  console.log('⚠️ 入库等待超时，继续（部分文档可能仍在处理）')
}

async function ensureDataset(kbId) {
  const { d } = await j('GET', '/api/v1/eval/datasets', { headers: H() })
  const list = Array.isArray(d?.data) ? d.data : (d?.data?.records || [])
  let ds = list.find(x => x.name === DS_NAME)
  if (ds) { console.log(`ℹ️ 数据集已存在 id=${ds.id}`); return ds.id }
  const r = await j('POST', '/api/v1/eval/datasets', { headers: H({ 'Content-Type': 'application/json' }), body: { name: DS_NAME, kbId } })
  if (!r.d?.data?.id) throw new Error('建数据集失败: ' + JSON.stringify(r.d).slice(0, 300))
  console.log(`✅ 新建数据集 id=${r.d.data.id}`)
  return r.d.data.id
}

async function importQuestions(dsId) {
  const { d: cur } = await j('GET', `/api/v1/eval/datasets/${dsId}/questions?page=1&size=500`, { headers: H() })
  const have = (cur?.data?.list || cur?.data?.records || cur?.data || []).length
  if (have >= 100) { console.log(`ℹ️ 已有 ${have} 题，跳过导入`); return }
  const all = JSON.parse(fs.readFileSync(QFILE, 'utf8'))
  // 分 5 批导入
  for (let i = 0; i < all.length; i += 20) {
    const batch = all.slice(i, i + 20)
    const r = await j('POST', `/api/v1/eval/datasets/${dsId}/questions/batch`, { headers: H({ 'Content-Type': 'application/json' }), body: batch })
    console.log(`  批量导入 ${i + 1}~${i + batch.length}: http=${r.status} 返回=${(r.d?.data || []).length}`)
    if (r.status !== 200) throw new Error('导入失败: ' + JSON.stringify(r.d).slice(0, 300))
    await sleep(300)
  }
  console.log('✅ 100 题导入完成')
}

async function verify(dsId) {
  const { d } = await j('GET', `/api/v1/eval/datasets/${dsId}/questions?page=1&size=500`, { headers: H() })
  const list = d?.data?.list || d?.data?.records || d?.data || []
  const enabled = list.filter(x => x.judgeEnabled).length
  const core = list.filter(x => x.isCore).length
  console.log(`📊 数据集校验: 总题=${list.length} judge_enabled=${enabled} is_core=${core}`)
  const { d: cnt } = await j('GET', '/api/v1/evaluation/golden-set/enabled-count', { headers: H() })
  console.log(`📊 本组织启用黄金题数(接口): ${JSON.stringify(cnt?.data)}`)
}

async function replay() {
  if (!DO_REPLAY) { console.log('（未带 --replay，跳过回放触发）'); return }
  const r = await j('POST', '/api/v1/evaluation/golden-set/replay/org', { headers: H() })
  console.log(`▶️ 触发回放: http=${r.status} ${JSON.stringify(r.d).slice(0, 200)}`)
}

(async () => {
  await login()
  const kbId = await ensureKb()
  await uploadCorpus(kbId)
  await waitIngest(kbId)
  const dsId = await ensureDataset(kbId)
  await importQuestions(dsId)
  await verify(dsId)
  await replay()
  console.log('\n🎉 加载流程结束')
})().catch(e => { console.error('❌ 出错:', e.message); process.exit(1) })
