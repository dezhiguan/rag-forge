import { expect, type APIRequestContext, type Page } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

export type ChunkVO = {
  chunkIndex?: number
  content?: string
  chunkModality?: string
  modality?: string
  imageKey?: string
}

export type ChunkRaw = {
  chunkId: number
  vlVectorDim: number
  modality: string
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
}

const ASSET_ROOT = path.resolve(import.meta.dirname, 'assets')
const API_BASE_URL = (process.env.RAGFORGE_E2E_API_BASE_URL || process.env.PLAYWRIGHT_BASE_URL || '').replace(/\/$/, '')

export function apiUrl(pathname: string) {
  return API_BASE_URL ? `${API_BASE_URL}${pathname}` : pathname
}

export function asset(name: string) {
  return path.join(ASSET_ROOT, name)
}

export async function login(request: APIRequestContext): Promise<Record<string, string>> {
  const envToken = process.env.RAGFORGE_E2E_TOKEN
  if (envToken) {
    return { Authorization: `Bearer ${envToken}` }
  }
  const account = process.env.RAGFORGE_E2E_USER || 'admin'
  const password = process.env.RAGFORGE_E2E_PASSWORD || 'admin'
  const res = await request.post(apiUrl('/api/auth/login'), {
    data: { account, password, remember: false },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  const body = await res.json()
  const token = body?.data?.access_token || body?.data?.accessToken || body?.access_token || body?.accessToken
  expect(token, 'login response must contain access token').toBeTruthy()
  return { Authorization: `Bearer ${token}` }
}

export async function createKb(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  imageMode: 'OFF' | 'ON' = 'OFF',
) {
  const res = await request.post(apiUrl('/api/v1/kb'), {
    headers,
    data: {
      name: `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      description: 'T10 rewrite Playwright E2E',
      chunkSize: 256,
      chunkOverlap: 24,
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  const kb = unwrap(await res.json())
  await setKbImageMode(request, headers, kb.id, imageMode)
  return kb.id as number
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
    // best-effort cleanup
  }
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
  const meta = {
    kbId,
    identity: {
      externalId: identity.externalId || `t10rw-${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      sourceUrl: identity.sourceUrl,
      contentMd5: identity.contentMd5,
    },
    onConflict: 'REPLACE',
    ingestSource: 't10-rewrite-e2e',
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
    timeout: 120_000,
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  const body = await res.json()
  expect(body.documentId).toBeTruthy()
  return body.documentId
}

export async function waitForStatus(
  request: APIRequestContext,
  headers: Record<string, string>,
  docId: number,
  expected: string,
  timeoutMs = 180_000,
) {
  await expect
    .poll(async () => (await getStatus(request, headers, docId)).parseStatus, {
      timeout: timeoutMs,
      intervals: [2_000, 5_000],
    })
    .toBe(expected)
}

export async function getStatus(request: APIRequestContext, headers: Record<string, string>, docId: number) {
  const res = await request.get(apiUrl(`/api/v1/documents/${docId}/status`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function getChunks(request: APIRequestContext, headers: Record<string, string>, docId: number): Promise<ChunkVO[]> {
  const res = await request.get(apiUrl(`/api/v1/documents/${docId}/chunks?page=1&size=200`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  const page = unwrap(await res.json())
  return page?.records || page?.list || []
}

export async function getChunkRaw(request: APIRequestContext, headers: Record<string, string>, docId: number): Promise<ChunkRaw[]> {
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

export async function loginPage(page: Page) {
  const token = process.env.RAGFORGE_E2E_TOKEN
  if (token) {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.evaluate(async ({ accessToken }) => {
      const mod = await import('/src/composables/useAuth.js')
      mod.useAuth().setSession(accessToken, {
        displayName: 't10-e2e-admin',
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
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByLabel('账号 / 手机号 / 邮箱').fill(process.env.RAGFORGE_E2E_USER || 'admin')
  await page.getByLabel('密码').fill(process.env.RAGFORGE_E2E_PASSWORD || 'admin')
  await page.getByRole('button', { name: '登 录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

export function unwrap(body: any) {
  return body && Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : body
}

function mimeType(name: string) {
  const ext = path.extname(name).toLowerCase()
  if (ext === '.pdf') return 'application/pdf'
  if (ext === '.png') return 'image/png'
  if (ext === '.jpg' || ext === '.jpeg') return 'image/jpeg'
  return 'application/octet-stream'
}
