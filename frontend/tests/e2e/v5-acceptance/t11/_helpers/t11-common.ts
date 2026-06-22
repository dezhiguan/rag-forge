import { expect, type APIRequestContext, type Page, test as base } from '@playwright/test'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import {
  login,
  createKb,
  uploadFile,
  waitForStatus,
  cleanupKb,
  loginPage,
  apiUrl,
  asset as t8Asset,
} from '../../_helpers/t8-common'

const PG_HOST = process.env.RAGFORGE_PG_HOST || process.env.POSTGRES_HOST || '127.0.0.1'
const PG_PORT = process.env.RAGFORGE_PG_PORT || process.env.POSTGRES_PORT || '5432'
const PG_DATABASE = process.env.RAGFORGE_PG_DATABASE || process.env.POSTGRES_DB || 'ragforge'
const PG_USER = process.env.RAGFORGE_PG_USER || process.env.POSTGRES_USER || 'amy'
const PG_PASSWORD = process.env.RAGFORGE_PG_PASSWORD || process.env.POSTGRES_PASSWORD || 'amy'
const PSQL_BIN = process.env.PSQL_BIN || '/Applications/Postgres.app/Contents/Versions/latest/bin/psql'

function runPsql(sql: string): string {
  return execFileSync(
    PSQL_BIN,
    ['-h', PG_HOST, '-p', PG_PORT, '-d', PG_DATABASE, '-U', PG_USER, '-c', sql],
    {
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: PG_PASSWORD },
      stdio: ['ignore', 'pipe', 'pipe'],
    }
  )
}

export function enableKbAnswerMode(kbId: number) {
  runPsql(`UPDATE knowledge_bases SET answer_mode = 'ON' WHERE id = ${kbId}`)
}

export const test = base.extend({})
test.use({
  trace: 'on',
  screenshot: 'on',
  video: 'on',
})
export { expect }

// Re-export from t8-common for convenience
export { login, createKb, uploadFile, waitForStatus, cleanupKb, apiUrl }

const ASSET_ROOT = path.resolve(import.meta.dirname, '../fixtures')

export function asset(name: string) {
  return path.join(ASSET_ROOT, name)
}

export type SseEvent = {
  event: string
  data: any
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

/**
 * Navigate to AnswerPlayground page and login
 */
export async function openAnswerPlayground(page: Page) {
  // First login using t8-common login helper
  await loginPage(page)
  // Then navigate to answer playground
  await page.goto('/answer', { waitUntil: 'domcontentloaded' })
  // Wait for page to be fully loaded
  await page.waitForSelector('.answer-page', { timeout: 10_000 })
}

/**
 * Select a knowledge base in the playground
 */
export async function selectKb(page: Page, kbId: number) {
  const select = page.locator('.answer-controls select').first()
  await select.waitFor({ timeout: 5_000 })
  // The select option value should match kbId
  await select.selectOption(String(kbId))
}

/**
 * Select multiple KBs in the playground (if supported)
 */
export async function selectMultiKb(page: Page, kbIds: number[]) {
  // If multi-select is supported via checkboxes or multiple select
  for (const kbId of kbIds) {
    await selectKb(page, kbId)
  }
}

/**
 * Enter a query and submit
 */
export async function submitAnswer(
  page: Page,
  query: string,
  options: {
    strategy?: string
    answerMode?: 'ON' | 'PREVIEW'
    topK?: number
    maxTokens?: number
  } = {}
) {
  const { strategy = 'hybrid', answerMode = 'ON', topK = 10, maxTokens = 800 } = options

  // Fill query
  await page.locator('.query-box').fill(query)

  // Set options if needed (using default values usually)
  const strategySelect = page.locator('.field').nth(1).locator('select')
  if (await strategySelect.isVisible().catch(() => false)) {
    await strategySelect.selectOption(strategy)
  }

  const answerModeSelect = page.locator('.field').nth(2).locator('select')
  if (await answerModeSelect.isVisible().catch(() => false)) {
    await answerModeSelect.selectOption(answerMode)
  }

  // Click run button
  await page.locator('.run-btn').click()
}

/**
 * Wait for answer generation to complete
 */
export async function waitForAnswerComplete(page: Page, timeout = 120_000) {
  // Wait for running to finish
  await expect
    .poll(
      async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return text?.includes('生成中') === false
      },
      { timeout, intervals: [500, 1000] }
    )
    .toBe(true)

  // Wait for either answer to appear or error box
  await page
    .locator('.answer-text:not(.empty), .error-box')
    .first()
    .waitFor({ timeout: 10_000 })
}

/**
 * Capture SSE events by intercepting the answer request
 */
export async function captureSseEvents(
  page: Page,
  action: () => Promise<void>
): Promise<SseEvent[]> {
  const events: SseEvent[] = []

  // Setup route interception
  await page.route('**/api/v1/answer', async (route) => {
    const response = await route.fetch()
    const body = await response.text()

    // Parse SSE events
    const lines = body.split('\n\n')
    for (const frame of lines) {
      const eventMatch = frame.match(/^event:\s*(.+)$/m)
      const dataMatch = frame.match(/^data:\s*(.+)$/m)
      if (eventMatch && dataMatch) {
        let data: any = dataMatch[1]
        try {
          data = JSON.parse(data)
        } catch {
          // keep as string
        }
        events.push({ event: eventMatch[1], data })
      }
    }

    await route.fulfill({
      response,
      body,
    })
  })

  await action()
  await waitForAnswerComplete(page)

  return events
}

/**
 * Get current answer text from the UI
 */
export async function getAnswerText(page: Page): Promise<string> {
  const answerEl = page.locator('.answer-text')
  const text = await answerEl.textContent()
  return text?.trim() || ''
}

/**
 * Get citations from the UI
 */
export async function getCitations(page: Page) {
  const cards = page.locator('.citation-card')
  const count = await cards.count()
  const citations = []

  for (let i = 0; i < count; i++) {
    const card = cards.nth(i)
    const meta = await card.locator('.citation-meta').textContent()
    const snippet = await card.locator('.citation-body p').textContent()
    citations.push({
      index: i + 1,
      meta,
      snippet,
    })
  }

  return citations
}

/**
 * Check if PII is masked in text (returns true if masked)
 */
export function isPiiMasked(text: string): boolean {
  // Check for common phone masking patterns
  const maskedPhone = /\d{3}[\*\.]{4}\d{4}/
  // Check for masked email
  const maskedEmail = /[\*\.]+@/
  // Check for masked ID
  const maskedId = /\d{6}[\*\.]+\d{4}/

  // Check for raw PII (should NOT be present)
  const rawPhone = /1[3-9]\d{9}/
  const rawEmail = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/
  const rawId = /\d{17}[0-9Xx]/

  const hasMasking = maskedPhone.test(text) || maskedEmail.test(text) || maskedId.test(text)
  const hasRawPii = rawPhone.test(text) || rawEmail.test(text) || rawId.test(text)

  return hasMasking || !hasRawPii
}

/**
 * Extract raw PII from text for validation
 */
export function extractPii(text: string): { phones: string[]; emails: string[]; ids: string[] } {
  const phones = text.match(/1[3-9]\d{9}/g) || []
  const emails = text.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g) || []
  const ids = text.match(/\d{17}[0-9Xx]/g) || []
  return { phones, emails, ids }
}

/**
 * Create a KB with specific answer_mode and answer_model
 */
export async function createKbWithMode(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  answerMode: 'OFF' | 'PREVIEW' | 'ON' = 'ON',
  answerModel?: string
): Promise<number> {
  const kbId = await createKb(request, headers, name)

  // Set answer_mode via direct API or SQL if needed
  // For now, we rely on the default being created as ON
  // If we need custom mode, we might need to update via SQL

  return kbId
}

/**
 * Query answer_logs table via SQL
 */
export function queryAnswerLogs(
  whereClause: string,
  orderBy: string = 'created_at DESC',
  limit: number = 10
): string {
  return `SELECT * FROM answer_logs WHERE ${whereClause} ORDER BY ${orderBy} LIMIT ${limit}`
}

/**
 * Wait for a specific time and take periodic snapshots of answer text
 */
export async function captureAnswerSnapshots(
  page: Page,
  durationMs: number,
  intervalMs: number
): Promise<string[]> {
  const snapshots: string[] = []
  const start = Date.now()

  while (Date.now() - start < durationMs) {
    const text = await getAnswerText(page)
    snapshots.push(text)
    await page.waitForTimeout(intervalMs)
  }

  return snapshots
}

/**
 * Create KB and upload document, wait for processing
 */
export async function setupKbWithDocument(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  fixtureName: string,
  answerMode: 'OFF' | 'PREVIEW' | 'ON' = 'ON'
): Promise<{ kbId: number; docId: number }> {
  const kbId = await createKb(request, headers, name)
  const fixturePath = asset(fixtureName)
  const docId = await uploadFile(request, headers, kbId, fixturePath)

  // Wait for document to be processed
  await waitForStatus(request, headers, docId, 'COMPLETED')

  return { kbId, docId }
}

/**
 * Validate citation format [n] in answer
 */
export function extractCitations(answer: string): number[] {
  const matches = answer.match(/\[(\d+)\]/g)
  if (!matches) return []
  return matches.map((m) => parseInt(m.match(/\[(\d+)\]/)?.[1] || '0')).filter((n) => n > 0)
}

/**
 * Check if citations are unique and sequential-ish
 */
export function validateCitationFormat(citations: number[]): { valid: boolean; issues: string[] } {
  const issues: string[] = []

  if (citations.length === 0) {
    issues.push('No citations found')
    return { valid: false, issues }
  }

  // Check for duplicates
  const unique = new Set(citations)
  if (unique.size !== citations.length) {
    issues.push('Duplicate citation indices found')
  }

  // Check for reasonable range
  const max = Math.max(...citations)
  const min = Math.min(...citations)
  if (min < 1) {
    issues.push('Citation index less than 1')
  }
  if (max > 30) {
    issues.push('Citation index exceeds 30 (suspicious)')
  }

  return { valid: issues.length === 0, issues }
}
