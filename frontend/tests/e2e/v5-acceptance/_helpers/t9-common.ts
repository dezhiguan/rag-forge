import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import {
  apiUrl,
  asset as t8Asset,
  cleanupKb,
  login,
  loginPage,
  test,
  uploadFile,
  unwrap,
  waitForStatus,
} from './t8-common'

export { cleanupKb, expect, login, loginPage, test, uploadFile, waitForStatus }

export type ChunkerStrategyName =
  | 'MARKDOWN_HEADING'
  | 'RECURSIVE'
  | 'FIXED_WINDOW'
  | 'SEMANTIC'
  | 'TABLE_AWARE'

export type ChunkerProfilePayload = {
  defaultStrategy?: ChunkerStrategyName
  fallbackChain?: ChunkerStrategyName[]
  params?: {
    chunkSize?: number
    overlap?: number
    separators?: string[]
    maxHeadingLevel?: number
    simThreshold?: number
    tablePolicy?: 'WHOLE' | 'ROW'
  }
}

export type DbChunk = {
  chunkId: number
  chunkIndex: number
  content: string
  tokenCount: number
  chunkerStrategy: ChunkerStrategyName
  chunkerParamsJson: Record<string, any>
  headingPath: string | null
}

export type TimingRecord = {
  strategy: ChunkerStrategyName
  kbId: number
  docId: number
  elapsedMs: number
}

const T9_ASSET_ROOT = path.resolve(import.meta.dirname, '../t9/fixtures')
const PG_HOST = process.env.RAGFORGE_PG_HOST || process.env.POSTGRES_HOST || '127.0.0.1'
const PG_PORT = process.env.RAGFORGE_PG_PORT || process.env.POSTGRES_PORT || '5432'
const PG_DATABASE = process.env.RAGFORGE_PG_DATABASE || process.env.POSTGRES_DB || 'ragforge'
const PG_USER = process.env.RAGFORGE_PG_USER || process.env.POSTGRES_USER || 'amy'
const PG_PASSWORD = process.env.RAGFORGE_PG_PASSWORD || process.env.POSTGRES_PASSWORD || 'amy'
const PSQL_BIN = process.env.PSQL_BIN || '/Applications/Postgres.app/Contents/Versions/latest/bin/psql'

export const T9_FIXTURES = {
  markdownHeadings: 'chunk-markdown-headings.md',
  noHeadingsPlain: 'chunk-no-headings-plain.txt',
  mixedTable: 'chunk-mixed-table.md',
  extraLongParagraph: 'chunk-extra-long-paragraph.txt',
  shortFragments: 'chunk-short-fragments.txt',
  codeBlocks: 'chunk-code-blocks.md',
  semanticTopicShift: 'chunk-semantic-topic-shift.txt',
  deeplyNestedList: 'chunk-deeply-nested-list.md',
  bilingual: 'chunk-bilingual-cn-en.txt',
  largeTableOnly: 'chunk-large-table-only.md',
} as const

export function t9Asset(name: string) {
  return path.join(T9_ASSET_ROOT, name)
}

export function fixtureAsset(name: string) {
  return name in T9_FIXTURES ? t9Asset(name) : t8Asset(name)
}

export async function createKbWithChunkerProfile(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  profile: ChunkerProfilePayload = {},
) {
  const params = profile.params || {}
  const kbId = await createKbForT9(request, headers, name, params.chunkSize, params.overlap)
  await setKbChunkerProfile(kbId, profile)
  if (params.chunkSize || params.overlap) {
    const res = await request.put(apiUrl(`/api/v1/kb/${kbId}`), {
      headers,
      data: {
        chunkSize: params.chunkSize,
        chunkOverlap: params.overlap,
      },
    })
    expect(res.ok(), await res.text()).toBeTruthy()
  }
  return kbId
}

async function createKbForT9(
  request: APIRequestContext,
  headers: Record<string, string>,
  name: string,
  chunkSize = 500,
  chunkOverlap = 50,
) {
  const res = await request.post(apiUrl('/api/v1/kb'), {
    headers,
    data: {
      name: `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      description: 'T9 acceptance Playwright E2E',
      chunkSize,
      chunkOverlap,
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json()).id as number
}

export async function setKbChunkerProfile(kbId: number, profile: ChunkerProfilePayload = {}) {
  const defaulted = {
    defaultStrategy: profile.defaultStrategy || 'MARKDOWN_HEADING',
    fallbackChain: profile.fallbackChain || ['RECURSIVE', 'FIXED_WINDOW'],
    params: {
      chunkSize: 500,
      overlap: 50,
      separators: ['\n\n', '\n', '。', ','],
      maxHeadingLevel: 3,
      simThreshold: 0.65,
      tablePolicy: 'WHOLE',
      ...(profile.params || {}),
    },
  }
  const json = JSON.stringify(defaulted)
  runPsql(
    `UPDATE knowledge_bases
     SET chunker_profile_json='${escapeSql(json)}'::jsonb,
         chunk_size=${defaulted.params.chunkSize},
         chunk_overlap=${defaulted.params.overlap},
         updated_at=NOW()
     WHERE id=${Number(kbId)}`,
  )
}

export function clearKbChunkerProfile(kbId: number) {
  runPsql(`UPDATE knowledge_bases SET chunker_profile_json=NULL, updated_at=NOW() WHERE id=${Number(kbId)}`)
}

export async function uploadAndWait(
  request: APIRequestContext,
  headers: Record<string, string>,
  kbId: number,
  fixtureName: string,
  timeoutMs = 300_000,
) {
  const docId = await uploadFile(request, headers, kbId, t9Asset(fixtureName))
  await waitForStatus(request, headers, docId, 'COMPLETED', timeoutMs)
  return docId
}

export function getDbChunks(docId: number): DbChunk[] {
  return queryRows<DbChunk>(`
    SELECT id AS "chunkId",
           chunk_index AS "chunkIndex",
           content,
           token_count AS "tokenCount",
           chunker_strategy AS "chunkerStrategy",
           COALESCE(chunker_params_json, '{}'::jsonb) AS "chunkerParamsJson",
           heading_path AS "headingPath"
    FROM document_chunks
    WHERE doc_id = ${Number(docId)}
    ORDER BY chunk_index, id
  `)
}

export function getDocumentChunkCount(docId: number): number {
  const rows = queryRows<{ chunkCount: number }>(`
    SELECT chunk_count AS "chunkCount"
    FROM documents
    WHERE id = ${Number(docId)}
  `)
  return rows[0]?.chunkCount ?? 0
}

export function uniqueChunkIds(chunks: DbChunk[]) {
  return chunks.map((chunk) => chunk.chunkId)
}

export async function rechunkDocument(request: APIRequestContext, headers: Record<string, string>, docId: number) {
  const res = await request.post(apiUrl(`/api/v1/documents/${docId}/rechunk`), { headers })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function createEvalDataset(request: APIRequestContext, headers: Record<string, string>, kbId: number, name: string) {
  const res = await request.post(apiUrl('/api/v1/eval/datasets'), {
    headers,
    data: {
      kbId,
      name: `${name}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json()).id as number
}

export async function createEvalQuestions(
  request: APIRequestContext,
  headers: Record<string, string>,
  datasetId: number,
  questions: Array<{ question: string, expectedTextSnippets?: string[], expectedChunkIds?: number[] }>,
) {
  const res = await request.post(apiUrl(`/api/v1/eval/datasets/${datasetId}/questions/batch`), {
    headers,
    data: questions,
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function runChunkerAb(
  request: APIRequestContext,
  headers: Record<string, string>,
  datasetId: number,
  strategies: ChunkerStrategyName[],
  params: ChunkerProfilePayload['params'] = {},
) {
  const res = await request.post(apiUrl('/api/v1/evaluation/chunker-ab'), {
    headers,
    data: {
      evalDatasetId: datasetId,
      strategies,
      params: {
        chunkSize: 500,
        overlap: 50,
        separators: ['\n\n', '\n', '。', ','],
        simThreshold: 0.65,
        tablePolicy: 'WHOLE',
        ...params,
      },
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return unwrap(await res.json())
}

export async function screenshotT9(page: Page, testInfo: TestInfo, name: string) {
  const screenshotPath = testInfo.outputPath(`t9-${name}.png`)
  await page.screenshot({ path: screenshotPath, fullPage: true })
  return screenshotPath
}

export function expectStrategies(chunks: DbChunk[], strategy: ChunkerStrategyName) {
  expect(chunks.length).toBeGreaterThan(0)
  expect(new Set(chunks.map((chunk) => chunk.chunkerStrategy))).toEqual(new Set([strategy]))
}

export function expectNoStrategy(chunks: DbChunk[], strategy: ChunkerStrategyName) {
  expect(chunks.some((chunk) => chunk.chunkerStrategy === strategy)).toBeFalsy()
}

export function expectOverlap(previous: string, next: string, expectedOverlap: number) {
  const prefix = next.slice(0, expectedOverlap)
  const suffix = previous.slice(-expectedOverlap)
  expect(prefix).toBe(suffix)
}

export function tableLineCount(content: string) {
  return content.split(/\r?\n/).filter((line) => line.trim().startsWith('|') && line.trim().endsWith('|')).length
}

function queryRows<T>(sql: string): T[] {
  const wrapped = `SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (${sql}) t`
  const output = runPsql(wrapped).trim()
  return output ? JSON.parse(output) : []
}

function runPsql(sql: string) {
  return execFileSync(
    PSQL_BIN,
    ['-h', PG_HOST, '-p', PG_PORT, '-d', PG_DATABASE, '-U', PG_USER, '-t', '-A', '-q', '-c', sql],
    {
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: PG_PASSWORD },
      stdio: ['ignore', 'pipe', 'inherit'],
    },
  )
}

function escapeSql(value: string) {
  return value.replace(/'/g, "''")
}
