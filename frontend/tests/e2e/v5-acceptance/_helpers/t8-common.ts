import { expect, type APIRequestContext, type Page, type TestInfo, test as base } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

export const test = base
export { expect }

export type ChunkVO = {
  chunkIndex?: number
  content?: string
  chunkModality?: string
  chunkerStrategy?: string
  headingPath?: string
  modality?: string
}

export type CleanRegion = {
  startOffset?: number
  endOffset?: number
  reason?: string
  text?: string
}

export type CleanReport = {
  originalSample?: string
  cleanedSample?: string
  removedRegions?: CleanRegion[]
  piiHits?: Record<string, number>
  profile?: {
    l1Enabled?: boolean
    l2Enabled?: boolean
    l3Enabled?: boolean
    l4Enabled?: boolean
    piiPolicy?: string
    skipClean?: boolean
  }
  originalLength?: number
  cleanedLength?: number
  llmTokensUsed?: number
}

export type ChunkRaw = {
  chunkId?: number
  vlVectorDim?: number
  modality?: string
  chunkMetadataJson?: string
}

export type CleanProfilePayload = {
  l1Enabled?: boolean
  l2Enabled?: boolean
  l3Enabled?: boolean
  l4Enabled?: boolean
  piiPolicy?: 'MASK' | 'HASH' | 'REJECT'
  skipClean?: boolean
}

const ASSET_ROOT = path.resolve(import.meta.dirname, '../t8/fixtures')
const API_BASE_URL = (process.env.RAGFORGE_E2E_API_BASE_URL || process.env.PLAYWRIGHT_BASE_URL || '').replace(/\/$/, '')

const PG_HOST = process.env.RAGFORGE_PG_HOST || process.env.POSTGRES_HOST || '127.0.0.1'
const PG_PORT = process.env.RAGFORGE_PG_PORT || process.env.POSTGRES_PORT || '5432'
const PG_DATABASE = process.env.RAGFORGE_PG_DATABASE || process.env.POSTGRES_DB || 'ragforge'
const PG_USER = process.env.RAGFORGE_PG_USER || process.env.POSTGRES_USER || 'amy'
const PG_PASSWORD = process.env.RAGFORGE_PG_PASSWORD || process.env.POSTGRES_PASSWORD || 'amy'
const PSQL_BIN = process.env.PSQL_BIN || '/Applications/Postgres.app/Contents/Versions/latest/bin/psql'

export function apiUrl(pathname: string) {
  return API_BASE_URL ? `${API_BASE_URL}${pathname}` : pathname
}

export function asset(name: string) {
  return path.join(ASSET_ROOT, name)
}

export function parseCleanReport(doc: any): CleanReport | null {
  if (!doc || !doc.cleanReportJson) return null
  if (typeof doc.cleanReportJson === 'object') {
    return doc.cleanReportJson as CleanReport
  }
  try {
    return JSON.parse(doc.cleanReportJson as string)
  } catch {
    return null
  }
}

export function cleanProfileDefaults(): CleanProfilePayload {
  return {
    l1Enabled: true,
    l2Enabled: true,
    l3Enabled: true,
    l4Enabled: false,
    piiPolicy: 'MASK',
    skipClean: false,
  }
}

export async function login(request: APIRequestContext): Promise<Record<string, string>> {
  const envToken = process.env.RAGFORGE_E2E_TOKEN
  if (envToken) {
    return { Authorization: `Bearer ${envToken}` }
  }
  const account = process.env.RAGFORGE_E2E_USER || 'admin'
  const password = process.env.RAGFORGE_E2E_PASSWORD || 'admin'
  // The backend proxies auth to the local auth-gateway, which can transiently time out
  // ("认证代理不可用"). Retry a few times with backoff so a single hiccup does not fail the case.
  let lastText = ''
  for (let attempt = 1; attempt <= 5; attempt++) {
    const res = await request.post(apiUrl('/api/auth/login'), {
      data: { account, password, remember: false },
      timeout: 30_000,
    })
    if (res.ok()) {
      const body = await res.json()
      const token = body?.data?.access_token || body?.data?.accessToken || body?.access_token || body?.accessToken
      expect(token, 'login response must contain access token').toBeTruthy()
      return { Authorization: `Bearer ${token}` }
    }
    lastText = await res.text()
    if (attempt < 5) {
      await new Promise((resolve) => setTimeout(resolve, attempt * 2_000))
    }
  }
  expect(false, `login failed after retries: ${lastText}`).toBeTruthy()
  throw new Error('unreachable')
}

export async function createKb(request: APIRequestContext, headers: Record<string, string>, name: string, imageMode: 'OFF' | 'ON' = 'OFF') {
  const res = await request.post(apiUrl('/api/v1/kb'), {
    headers,
    data: {
      name: `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      description: 'T8 acceptance Playwright E2E',
      chunkSize: 256,
      chunkOverlap: 24,
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  const kb = unwrap(await res.json())
  await setKbImageMode(request, headers, kb.id, imageMode)
  return kb.id as number
}

export async function setKbImageMode(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  mode: 'OFF' | 'ON',
) {
  const res = await request.post(apiUrl(`/api/v1/admin/e2e/kb/${kbId}/image-mode`), {
    headers,
    data: { mode },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
}

export async function uploadFile(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  filePath: string,
  identity: Record<string, string> = {},
): Promise<number> {
  const name = path.basename(filePath)
  // Keep a stable externalId across retries so onConflict=REPLACE de-dupes instead of stacking docs.
  const externalId = identity.externalId || `t8-${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`
  const meta = {
    kbId,
    identity: {
      externalId,
      sourceUrl: identity.sourceUrl,
      contentMd5: identity.contentMd5,
    },
    onConflict: 'REPLACE',
    ingestSource: 't8-e2e',
    metadata: { fixture: name },
  }
  const buffer = fs.readFileSync(filePath)
  // The relay upload writes to object storage (Aliyun OSS) and the DB; both can transiently fail
  // ("DOCUMENT_UPLOAD_FAILED" 500). Retry a few times so a single network/connection hiccup does not
  // fail the case. REPLACE + stable externalId makes the retry idempotent.
  let lastText = ''
  for (let attempt = 1; attempt <= 4; attempt++) {
    const res = await request.post(apiUrl('/api/v1/documents'), {
      headers,
      multipart: {
        file: { name, mimeType: mimeType(name), buffer },
        meta: JSON.stringify(meta),
      },
      timeout: 180_000,
    })
    if (res.ok()) {
      const body = await res.json()
      expect(body.documentId, 'upload response should contain documentId').toBeTruthy()
      return body.documentId
    }
    lastText = await res.text()
    if (attempt < 4) {
      await new Promise((resolve) => setTimeout(resolve, attempt * 2_000))
    }
  }
  expect(false, `upload failed after retries: ${lastText}`).toBeTruthy()
  throw new Error('unreachable')
}

export async function waitForStatus(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
  expected: string,
  timeoutMs = 180_000,
) {
  const result = await expect
    .poll(async () => (await getStatus(request, headers, docId)).parseStatus, {
      timeout: timeoutMs,
      intervals: [2_000, 4_000],
    })
    .toBe(expected)
  return result
}

export async function waitForTerminalStatus(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
  timeoutMs = 180_000,
) {
  return expect
    .poll(async () => (await getStatus(request, headers, docId)).parseStatus, {
      timeout: timeoutMs,
      intervals: [2_000, 4_000],
    })
    .toMatch(/^(COMPLETED|FAILED)$/)
}

export async function getStatus(request: APIRequestContext, headers: Record<string, string>, docId: number) {
  const res = await request.get(apiUrl(`/api/v1/documents/${docId}/status`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function getDocument(request: APIRequestContext, headers: Record<string, string>, docId: number) {
  const res = await request.get(apiUrl(`/api/v1/documents/${docId}`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function getChunks(request: APIRequestContext, headers: Record<string, string>, docId: number): Promise<ChunkVO[]> {
  const res = await request.get(apiUrl(`/api/v1/documents/${docId}/chunks?page=1&size=200`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  const page = unwrap(await res.json())
  return page?.records || page?.list || []
}

export async function getChunkRaw(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
): Promise<ChunkRaw[]> {
  const res = await request.get(apiUrl(`/api/v1/admin/e2e/chunks/${docId}/raw`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function cleanupKb(request: APIRequestContext, headers: Record<string, string>, kbId: number) {
  try {
    const docs = await request.get(apiUrl(`/api/v1/kb/${kbId}/documents?page=1&size=200`), { headers })
    if (docs.ok()) {
      const page = unwrap(await docs.json())
      for (const doc of page?.records || page?.list || []) {
        await request.delete(apiUrl(`/api/v1/documents/${doc.id}`), { headers })
      }
    }
    await request.delete(apiUrl(`/api/v1/kb/${kbId}`), { headers })
  } catch {
    // best effort cleanup
  }
}

export async function createCleanProfile(kbId: number, profile: CleanProfilePayload = cleanProfileDefaults()) {
  const defaulted = { ...cleanProfileDefaults(), ...profile }
  const config = JSON.stringify(defaulted)
  const sql = `INSERT INTO clean_profiles (scope, scope_id, config, created_at, updated_at)
    VALUES ('KB', ${kbId}, '${config.replace(/'/g, "''" )}'::jsonb, NOW(), NOW())
    ON CONFLICT (scope, scope_id)
    DO UPDATE SET config = EXCLUDED.config, updated_at = NOW()`
  runPsql(sql)
}

export function deleteCleanProfile(kbId: number) {
  try {
    runPsql(`DELETE FROM clean_profiles WHERE scope='KB' AND scope_id=${kbId}`)
  } catch {
    // best effort cleanup
  }
}

export async function openUploadDonePage(page: Page, kbId: number, filename: string) {
  await page.goto(`/knowledge/${kbId}/documents`, { waitUntil: 'domcontentloaded' })
  await page.getByText(filename, { exact: false }).first().waitFor({ timeout: 20_000 })
}

export async function loginPage(page: Page) {
  const token = process.env.RAGFORGE_E2E_TOKEN
  if (token) {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.evaluate(async ({ accessToken }) => {
      const mod = await import('/src/composables/useAuth.js')
      mod.useAuth().setSession(accessToken, {
        displayName: 't8-e2e-admin',
        ragRole: 'ADMIN',
        scopes: [
          'rag:dashboard:read',
          'rag:kb:create',
          'rag:kb:read',
          'rag:kb:write',
          'rag:doc:read',
          'rag:doc:write',
          'rag:debug:run',
          'rag:apikey:admin',
        ],
      })
      const router = (await import('/src/router/index.js')).default
      await router.push('/')
    }, { accessToken: token })
    await expect(page).toHaveURL(/\/$/)
    return
  }

  const user = process.env.RAGFORGE_E2E_USER || 'admin'
  const password = process.env.RAGFORGE_E2E_PASSWORD || 'admin'
  // The backend auth proxy (auth-gateway) can transiently time out ("认证代理不可用"). The UI form
  // login persists the session correctly across reloads, so we keep it but retry on a transient miss.
  let lastErr: unknown
  for (let attempt = 1; attempt <= 4; attempt++) {
    try {
      await page.goto('/login', { waitUntil: 'domcontentloaded' })
      await page.getByLabel('账号 / 手机号 / 邮箱').fill(user)
      await page.getByLabel('密码').fill(password)
      await page.getByRole('button', { name: '登 录' }).click()
      await expect(page).toHaveURL(/\/$/, { timeout: 15_000 })
      return
    } catch (err) {
      lastErr = err
      if (attempt < 4) {
        await page.waitForTimeout(attempt * 2_000)
      }
    }
  }
  throw lastErr
}

export async function screenshotPair(page: Page, testInfo: TestInfo, docId: number, kbId: number, suffix = 'case') {
  const kbListPath = testInfo.outputPath(`t8-${suffix}-kb-${kbId}.png`)
  const chunksPath = testInfo.outputPath(`t8-${suffix}-doc-${docId}-chunks.png`)

  await page.goto(`/knowledge/${kbId}/documents`, { waitUntil: 'domcontentloaded' })
  await page.screenshot({ path: kbListPath, fullPage: true })

  await page.goto(`/document/${docId}`, { waitUntil: 'domcontentloaded' })
  await page.screenshot({ path: chunksPath, fullPage: true })
  return { kbListPath, chunksPath }
}

export async function listContainsText(page: Page, text: string) {
  await page.getByText(text, { exact: false }).first().waitFor({ timeout: 10_000 })
}

export function unwrap(body: any) {
  return body && Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : body
}

function mimeType(name: string) {
  const ext = path.extname(name).toLowerCase()
  if (ext === '.pdf') return 'application/pdf'
  if (ext === '.txt') return 'text/plain'
  if (ext === '.md') return 'text/markdown'
  if (ext === '.png') return 'image/png'
  if (ext === '.jpg' || ext === '.jpeg') return 'image/jpeg'
  return 'application/octet-stream'
}

function runPsql(sql: string) {
  execFileSync(
    PSQL_BIN,
    ['-h', PG_HOST, '-p', PG_PORT, '-d', PG_DATABASE, '-U', PG_USER, '-c', sql],
    {
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: PG_PASSWORD },
      stdio: ['ignore', 'pipe', 'inherit'],
    },
  )
}
