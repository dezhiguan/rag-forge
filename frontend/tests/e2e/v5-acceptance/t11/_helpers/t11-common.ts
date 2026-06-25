import { expect, type APIRequestContext, type Page, test as base } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync, spawnSync } from 'node:child_process'
import {
  login,
  uploadFile,
  waitForStatus,
  cleanupKb as cleanupKbRaw,
  loginPage,
  apiUrl,
  unwrap,
} from '../../_helpers/t8-common'

const PG_HOST = process.env.RAGFORGE_PG_HOST || process.env.POSTGRES_HOST || '127.0.0.1'
const PG_PORT = process.env.RAGFORGE_PG_PORT || process.env.POSTGRES_PORT || '5432'
const PG_DATABASE = process.env.RAGFORGE_PG_DATABASE || process.env.POSTGRES_DB || 'ragforge'
const PG_USER = process.env.RAGFORGE_PG_USER || process.env.POSTGRES_USER || 'amy'
const PG_PASSWORD = process.env.RAGFORGE_PG_PASSWORD || process.env.POSTGRES_PASSWORD || 'amy'
const PSQL_BIN = process.env.PSQL_BIN || '/Applications/Postgres.app/Contents/Versions/latest/bin/psql'

const ASSET_ROOT = path.resolve(import.meta.dirname, '../fixtures')
const SINGLE_KB_ID_FILE = path.resolve(
  import.meta.dirname,
  '../../../../test-results/v5-acceptance/t11/t11-single-kb-id.txt',
)

const ALL_FIXTURES = [
  'answer-kb-tech-faq.txt',
  'answer-kb-pii-mixed.txt',
  'answer-kb-multilingual.txt',
  'answer-kb-numerical.txt',
  'answer-kb-conflicting.txt',
  // empty.txt often fails parsing — acc-03 uploads it separately when not in single-KB mode
]

let singleKbInit: Promise<number> | null = null

export const test = base.extend({})
export { expect }

export { login, uploadFile, waitForStatus, apiUrl }

export type SseEvent = {
  event: string
  data: unknown
}

export type AnswerResponse = {
  answer: string
  citations: Array<{
    id: number
    chunkId: number
    docId: number
    modality: string
    textSnippet: string
    imageUrl?: string
  }>
  latency: {
    retrieval: number
    llm: number
    total: number
  }
  tokens: {
    prompt: number
    completion: number
  }
  guardRailResult: string
  llmModel: string
}

function runPsql(sql: string): string {
  return execFileSync(
    PSQL_BIN,
    ['-h', PG_HOST, '-p', PG_PORT, '-d', PG_DATABASE, '-U', PG_USER, '-c', sql],
    {
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: PG_PASSWORD },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  )
}

export function psqlAvailable(): boolean {
  try {
    runPsql('SELECT 1')
    return true
  } catch {
    return false
  }
}

export function runPsqlQuery(sql: string): string {
  return runPsql(sql)
}

export function enableKbAnswerMode(kbId: number) {
  if (psqlAvailable()) {
    runPsql(`UPDATE knowledge_bases SET answer_mode = 'ON' WHERE id = ${kbId}`)
    return
  }
  throw new Error(`psql unavailable — use updateKbAnswerConfig() for kbId=${kbId}`)
}

export async function updateKbAnswerConfig(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  config: { answerMode?: 'OFF' | 'PREVIEW' | 'ON'; answerModel?: string },
) {
  const res = await request.put(apiUrl(`/api/v1/kb/${kbId}`), { headers, data: config })
  expect(res.ok(), await res.text()).toBeTruthy()
}

export async function ensureKbAnswerOn(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
) {
  if (psqlAvailable()) {
    try {
      runPsql(`UPDATE knowledge_bases SET answer_mode = 'ON' WHERE id = ${kbId}`)
      return
    } catch {
      // fall through
    }
  }
  await updateKbAnswerConfig(request, headers, kbId, { answerMode: 'ON' })
}

export function asset(name: string) {
  return path.join(ASSET_ROOT, name)
}

export async function createAnswerKb(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  options: { answerMode?: 'OFF' | 'PREVIEW' | 'ON'; answerModel?: string } = {},
): Promise<number> {
  const res = await request.post(apiUrl('/api/v1/kb'), {
    headers,
    data: {
      name: `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      description: 'T11 acceptance Playwright E2E',
      chunkSize: 256,
      chunkOverlap: 24,
      answerMode: options.answerMode ?? 'ON',
      ...(options.answerModel ? { answerModel: options.answerModel } : {}),
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  const kb = unwrap(await res.json())
  return kb.id as number
}

async function initSingleKb(request: APIRequestContext, headers: Record<string, string>): Promise<number> {
  const preset = process.env.RAGFORGE_T11_SINGLE_KB_ID
  if (preset) {
    return Number(preset)
  }
  if (fs.existsSync(SINGLE_KB_ID_FILE)) {
    const id = Number(fs.readFileSync(SINGLE_KB_ID_FILE, 'utf8').trim())
    if (id > 0) {
      return id
    }
  }

  const kbId = await createAnswerKb(request, headers, 't11-acceptance-all', { answerMode: 'ON' })
  for (const fixture of ALL_FIXTURES) {
    const docId = await uploadFile(request, headers, kbId, asset(fixture), {
      externalId: `t11-shared-${fixture}`,
    })
    await waitForStatus(request, headers, docId, 'COMPLETED')
  }

  fs.mkdirSync(path.dirname(SINGLE_KB_ID_FILE), { recursive: true })
  fs.writeFileSync(SINGLE_KB_ID_FILE, String(kbId))
  console.log(`[t11-e2e] single shared KB ready: kbId=${kbId}`)
  return kbId
}

export async function getSharedKbId(
  request: APIRequestContext,
  headers: Record<string, string>,
): Promise<number> {
  if (!singleKbInit) {
    singleKbInit = initSingleKb(request, headers)
  }
  return singleKbInit
}

export async function createKb(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  answerMode: 'OFF' | 'PREVIEW' | 'ON' = 'ON',
): Promise<number> {
  if (process.env.RAGFORGE_T11_SINGLE_KB === '1') {
    return getSharedKbId(request, headers)
  }
  return createAnswerKb(request, headers, name, { answerMode })
}

export async function cleanupKb(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
) {
  if (process.env.RAGFORGE_E2E_SKIP_CLEANUP === '1') {
    console.log(`[t11-e2e] skip cleanup: keep kbId=${kbId}`)
    return
  }
  await cleanupKbRaw(request, headers, kbId)
}

function curlAnswerSse(url: string, headers: Record<string, string>, payload: Record<string, unknown>): string {
  const body = JSON.stringify(payload)
  const { stdout, stderr, status } = spawnSync(
    'curl',
    [
      '-s',
      '-N',
      '--max-time',
      '180',
      '-X',
      'POST',
      url,
      '-H',
      `Authorization: ${headers.Authorization}`,
      '-H',
      'Content-Type: application/json',
      '-d',
      body,
    ],
    { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 },
  )
  const result = stdout || ''
  if (!result.includes('event:')) {
    throw new Error(`curl SSE failed status=${status}: ${stderr || result.slice(0, 200)}`)
  }
  return result
}

export function parseSseBody(body: string): SseEvent[] {
  const events: SseEvent[] = []
  for (const frame of body.split('\n\n')) {
    const eventMatch = frame.match(/^event:\s*(.+)$/m)
    const dataMatch = frame.match(/^data:\s*(.+)$/m)
    if (eventMatch && dataMatch) {
      let data: unknown = dataMatch[1]
      try {
        data = JSON.parse(data)
      } catch {
        // keep string
      }
      events.push({ event: eventMatch[1].trim(), data })
    }
  }
  return events
}

export async function postAnswerSse(
  _request: APIRequestContext,
  headers: Record<string, string>,
  payload: Record<string, unknown>,
) {
  const text = curlAnswerSse(apiUrl('/api/v1/answer'), headers, payload)
  const events = parseSseBody(text)
  const complete = events.find((e) => e.event === 'complete')?.data as AnswerResponse | undefined
  const errorEv = events.find((e) => e.event === 'error')
  return { status: complete || errorEv ? 200 : 500, events, complete, errorEv, text }
}

export async function attachAnswerSseProxy(page: Page, headers: Record<string, string>) {
  await page.route('**/api/v1/answer', async (route) => {
    const postData = route.request().postDataJSON() as Record<string, unknown>
    const body = curlAnswerSse(apiUrl('/api/v1/answer'), headers, postData)
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
      body,
    })
  })
}

export async function openAnswerPlayground(page: Page, headers?: Record<string, string>) {
  if (headers?.Authorization) {
    await attachAnswerSseProxy(page, headers)
  }
  let lastErr: unknown
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await loginPage(page)
      await page.goto('/answer', { waitUntil: 'domcontentloaded' })
      await page.waitForSelector('.answer-page', { timeout: 30_000 })
      await page.locator('.answer-controls .field').first().locator('select').waitFor({ state: 'attached', timeout: 15_000 })
      return
    } catch (err) {
      lastErr = err
      if (attempt < 3) await page.waitForTimeout(attempt * 2_000)
    }
  }
  throw lastErr
}

export async function selectKb(page: Page, kbId: number) {
  const select = page.locator('.answer-controls .field').first().locator('select')
  await select.waitFor({ timeout: 5_000 })
  await select.selectOption(String(kbId))
}

export async function selectMultiKb(page: Page, kbIds: number[]) {
  for (const kbId of kbIds) {
    await selectKb(page, kbId)
  }
}

export async function submitAnswer(
  page: Page,
  query: string,
  options: {
    strategy?: string
    answerMode?: 'ON' | 'PREVIEW'
    topK?: number
    maxTokens?: number
  } = {},
) {
  const { strategy = 'hybrid', answerMode = 'ON', topK = 10, maxTokens = 800 } = options
  await page.locator('.query-box').fill(query)
  const strategySelect = page.locator('.field').nth(1).locator('select')
  if (await strategySelect.isVisible().catch(() => false)) {
    await strategySelect.selectOption(strategy)
  }
  const answerModeSelect = page.locator('.field').nth(2).locator('select')
  if (await answerModeSelect.isVisible().catch(() => false)) {
    await answerModeSelect.selectOption(answerMode)
  }
  await page.locator('.run-btn').click()
}

export async function waitForAnswerComplete(page: Page, timeout = 120_000) {
  await expect
    .poll(
      async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return text?.includes('生成中') === false
      },
      { timeout, intervals: [500, 1000] },
    )
    .toBe(true)
  await page.locator('.answer-text:not(.empty), .error-box').first().waitFor({ timeout: 10_000 })
}

export async function captureSseEvents(page: Page, action: () => Promise<void>): Promise<SseEvent[]> {
  const events: SseEvent[] = []
  await page.route('**/api/v1/answer', async (route) => {
    const postData = route.request().postDataJSON() as Record<string, unknown>
    const body = curlAnswerSse(apiUrl('/api/v1/answer'), { Authorization: route.request().headers()['authorization'] || '' }, postData)
    for (const ev of parseSseBody(body)) {
      events.push(ev)
    }
    await route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body })
  })
  await action()
  await waitForAnswerComplete(page)
  return events
}

export async function getAnswerText(page: Page): Promise<string> {
  const answerEl = page.locator('.answer-text')
  const text = await answerEl.textContent()
  return text?.trim() || ''
}

export async function getCitations(page: Page) {
  const cards = page.locator('.citation-card')
  const count = await cards.count()
  const citations = []
  for (let i = 0; i < count; i++) {
    const card = cards.nth(i)
    const meta = await card.locator('.citation-meta').textContent()
    const snippet = await card.locator('.citation-body p').textContent()
    citations.push({ index: i + 1, meta, snippet })
  }
  return citations
}

export function isPiiMasked(text: string): boolean {
  const maskedPhone = /\d{3}[\*\.]{4}\d{4}/
  const maskedEmail = /[\*\.]+@/
  const maskedId = /\d{6}[\*\.]+\d{4}/
  const rawPhone = /1[3-9]\d{9}/
  const rawEmail = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/
  const rawId = /\d{17}[0-9Xx]/
  const hasMasking = maskedPhone.test(text) || maskedEmail.test(text) || maskedId.test(text)
  const hasRawPii = rawPhone.test(text) || rawEmail.test(text) || rawId.test(text)
  return hasMasking || !hasRawPii
}

export function extractPii(text: string): { phones: string[]; emails: string[]; ids: string[] } {
  const phones = text.match(/1[3-9]\d{9}/g) || []
  const emails = text.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g) || []
  const ids = text.match(/\d{17}[0-9Xx]/g) || []
  return { phones, emails, ids }
}

export async function createKbWithMode(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  answerMode: 'OFF' | 'PREVIEW' | 'ON' = 'ON',
  answerModel?: string,
): Promise<number> {
  if (process.env.RAGFORGE_T11_SINGLE_KB === '1') {
    return getSharedKbId(request, headers)
  }
  return createAnswerKb(request, headers, name, { answerMode, answerModel })
}

export function queryAnswerLogs(whereClause: string, orderBy = 'created_at DESC', limit = 10): string {
  return `SELECT * FROM answer_logs WHERE ${whereClause} ORDER BY ${orderBy} LIMIT ${limit}`
}

export async function captureAnswerSnapshots(
  page: Page,
  durationMs: number,
  intervalMs: number,
): Promise<string[]> {
  const snapshots: string[] = []
  const start = Date.now()
  while (Date.now() - start < durationMs) {
    snapshots.push(await getAnswerText(page))
    await page.waitForTimeout(intervalMs)
  }
  return snapshots
}

export async function setupKbWithDocument(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  fixtureName: string,
  answerMode: 'OFF' | 'PREVIEW' | 'ON' = 'ON',
): Promise<{ kbId: number; docId: number }> {
  const kbId = await createKb(request, headers, name, answerMode)
  if (process.env.RAGFORGE_T11_SINGLE_KB === '1') {
    return { kbId, docId: 0 }
  }
  const docId = await uploadFile(request, headers, kbId, asset(fixtureName))
  await waitForStatus(request, headers, docId, 'COMPLETED')
  return { kbId, docId }
}

export function extractCitations(answer: string): number[] {
  const matches = answer.match(/\[(\d+)\]/g)
  if (!matches) return []
  return matches.map((m) => parseInt(m.match(/\[(\d+)\]/)?.[1] || '0', 10)).filter((n) => n > 0)
}

export function validateCitationFormat(citations: number[]): { valid: boolean; issues: string[] } {
  const issues: string[] = []
  if (citations.length === 0) {
    issues.push('No citations found')
    return { valid: false, issues }
  }
  const unique = new Set(citations)
  if (unique.size !== citations.length) {
    issues.push('Duplicate citation indices found')
  }
  const max = Math.max(...citations)
  const min = Math.min(...citations)
  if (min < 1) issues.push('Citation index less than 1')
  if (max > 30) issues.push('Citation index exceeds 30 (suspicious)')
  return { valid: issues.length === 0, issues }
}
