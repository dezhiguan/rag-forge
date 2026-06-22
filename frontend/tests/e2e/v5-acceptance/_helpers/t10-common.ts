import { expect, type APIRequestContext, type Page, type TestInfo, test as base } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import {
  apiUrl,
  cleanProfileDefaults,
  createCleanProfile,
  deleteCleanProfile,
  login,
  loginPage,
  unwrap,
} from './t8-common'

export const test = base.extend({})
test.use({
  trace: 'on',
  screenshot: 'on',
  video: 'on',
})
export { expect }
export {
  apiUrl,
  cleanProfileDefaults,
  createCleanProfile,
  deleteCleanProfile,
  login,
  loginPage,
  unwrap,
}

export type ChunkVO = {
  chunkIndex?: number
  content?: string
  chunkModality?: string
  modality?: string
  imageKey?: string
  tokenCount?: number
}

export type ChunkRaw = {
  chunkId?: number
  vlVectorDim?: number
  modality?: string
  chunkMetadataJson?: string
}

export type SearchResult = {
  chunkId: number
  docId: number
  filename: string
  content: string
  vectorScore?: number
  finalScore?: number
  chunkModality?: string
  imageKey?: string
}

const ASSET_ROOT = path.resolve(import.meta.dirname, '../t10/fixtures')
const PG_HOST = process.env.RAGFORGE_PG_HOST || process.env.POSTGRES_HOST || '127.0.0.1'
const PG_PORT = process.env.RAGFORGE_PG_PORT || process.env.POSTGRES_PORT || '5432'
const PG_DATABASE = process.env.RAGFORGE_PG_DATABASE || process.env.POSTGRES_DB || 'ragforge'
const PG_USER = process.env.RAGFORGE_PG_USER || process.env.POSTGRES_USER || 'amy'
const PG_PASSWORD = process.env.RAGFORGE_PG_PASSWORD || process.env.POSTGRES_PASSWORD || 'amy'
const PSQL_BIN = process.env.PSQL_BIN || '/Applications/Postgres.app/Contents/Versions/latest/bin/psql'

export function asset(name: string) {
  return path.join(ASSET_ROOT, name)
}

export async function createKb(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  imageMode: 'OFF' | 'ON' = 'ON',
) {
  const res = await request.post(apiUrl('/api/v1/kb'), {
    headers,
    data: {
      name: `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      description: 'T10 v5 acceptance Playwright E2E',
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
  if (res.ok()) return
  try {
    runPsql(
      `UPDATE knowledge_bases SET image_processing_mode='${mode}', updated_at=NOW() WHERE id=${kbId}`,
    )
  } catch (error) {
    expect(res.ok(), `${await res.text()} ; psql fallback failed: ${error}`).toBeTruthy()
  }
}

export async function uploadFile(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  filePath: string,
  identity: Record<string, string> = {},
): Promise<number> {
  const name = path.basename(filePath)
  const meta = {
    kbId,
    identity: {
      externalId: identity.externalId || `t10acc-${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      sourceUrl: identity.sourceUrl,
      contentMd5: identity.contentMd5,
    },
    onConflict: 'REPLACE',
    ingestSource: 't10-v5-e2e',
    metadata: { fixture: name },
  }
  const res = await request.post(apiUrl('/api/v1/documents'), {
    headers,
    multipart: {
      file: {
        name,
        mimeType: mimeType(name),
        buffer: fs.readFileSync(filePath),
      },
      meta: JSON.stringify(meta),
    },
    timeout: 180_000,
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  const body = await res.json()
  expect(body.documentId, 'upload response should contain documentId').toBeTruthy()
  return body.documentId
}

export async function waitForStatus(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
  expected: string,
  timeoutMs = 240_000,
) {
  await expect
    .poll(async () => (await getStatus(request, headers, docId)).parseStatus, {
      timeout: timeoutMs,
      intervals: [2_000, 4_000],
    })
    .toBe(expected)
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

export async function searchByText(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  query: string,
  strategy = 'hybrid',
  topK = 8,
): Promise<SearchResult[]> {
  const res = await request.post(apiUrl('/api/v1/search'), {
    headers,
    data: { query, kbIds: [kbId], strategy, topK },
    timeout: 120_000,
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json()).results || []
}

export async function searchByImage(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  imageBytes: Buffer,
  topK = 8,
): Promise<SearchResult[]> {
  const res = await request.post(apiUrl('/api/v1/search/by-image'), {
    headers,
    data: {
      kbIds: [kbId],
      topK,
      queryImageBase64: `data:image/png;base64,${imageBytes.toString('base64')}`,
    },
    timeout: 120_000,
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json()).results || []
}

export async function reprocess(request: APIRequestContext, headers: Record<string, string>, docId: number) {
  const res = await request.post(apiUrl(`/api/v1/documents/${docId}/reprocess`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
}

export async function setDocumentStatus(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
  status: string,
) {
  const res = await request.post(apiUrl(`/api/v1/admin/e2e/documents/${docId}/status`), {
    headers,
    data: { status },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
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

export async function fetchPrometheus(request: APIRequestContext, headers: Record<string, string>) {
  const res = await request.get(apiUrl('/actuator/prometheus'), { headers, timeout: 30_000 })
  expect(res.ok(), await res.text()).toBeTruthy()
  return res.text()
}

export async function verifyDocumentDownload(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
) {
  const res = await request.get(apiUrl(`/api/v1/documents/${docId}/download`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  const body = await res.body()
  expect(body.byteLength, 'download body should not be empty').toBeGreaterThan(100)
}

export async function openDocumentDetail(page: Page, docId: number) {
  if (process.env.RAGFORGE_E2E_TOKEN) {
    await page.evaluate(async ({ target }) => {
      const router = (await import('/src/router/index.js')).default
      await router.push(target)
    }, { target: `/document/${docId}` })
    await expect(page).toHaveURL(new RegExp(`/document/${docId}$`))
    return
  }
  await page.goto(`/document/${docId}`, { waitUntil: 'domcontentloaded' })
}

export async function openDebugConsole(page: Page) {
  if (process.env.RAGFORGE_E2E_TOKEN) {
    await page.evaluate(async () => {
      const router = (await import('/src/router/index.js')).default
      await router.push('/debug')
    }, {})
    await expect(page).toHaveURL(/\/debug$/)
    return
  }
  await page.goto('/debug', { waitUntil: 'domcontentloaded' })
}

export async function screenshotDocumentDetail(
  page: Page,
  testInfo: TestInfo,
  docId: number,
  kbId: number,
  suffix: string,
) {
  await openDocumentDetail(page, docId)
  await page.getByText('Chunks', { exact: false }).first().waitFor({ timeout: 20_000 })
  const pathOut = testInfo.outputPath(`t10-${suffix}-doc-${docId}-kb-${kbId}.png`)
  await page.screenshot({ path: pathOut, fullPage: true })
  return pathOut
}

function mimeType(name: string) {
  const ext = path.extname(name).toLowerCase()
  if (ext === '.pdf') return 'application/pdf'
  if (ext === '.txt') return 'text/plain'
  if (ext === '.md') return 'text/markdown'
  if (ext === '.html') return 'text/html'
  if (ext === '.docx') return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
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
