/**
 * T11 ACC-07: 默认模型 qwen-plus 验证
 * 目标：KB 未显式设 answer_model 时必须默认走 qwen-plus
 */
import {
  test,
  expect,
  login,
  createKb,
  uploadFile,
  waitForStatus,
  cleanupKb,
  openAnswerPlayground,
  selectKb,
  submitAnswer,
  getAnswerText,
  asset,
  apiUrl,
} from './_helpers/t11-common'
import { execFileSync } from 'node:child_process'

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

test.describe('T11 ACC-07 Default Model qwen-plus', () => {
  let kbId: number
  let techDocId: number
  let headers: Record<string, string>

  test.beforeAll(async ({ request }) => {
    headers = await login(request)
  })

  test.beforeEach(async ({ request }) => {
    // Create KB and ensure answer_model is NULL (default)
    kbId = await createKb(request, headers, 't11-acc-07-default-model')

    // Set answer_model to NULL to test default behavior
    runPsql(`UPDATE knowledge_bases SET answer_model = NULL WHERE id = ${kbId}`)

    techDocId = await uploadFile(request, headers, kbId, asset('answer-kb-tech-faq.txt'))
    await waitForStatus(request, headers, techDocId, 'COMPLETED')
  })

  test.afterEach(async ({ request }) => {
    await cleanupKb(request, headers, kbId)
  })

  test('answer_logs should record qwen-plus when KB has no explicit answer_model', async ({ request }) => {
    // Make a query
    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: 'Spring Boot 默认端口',
        stream: false,
      },
    })

    expect(response.ok()).toBeTruthy()

    // Wait for log to be written
    await new Promise((r) => setTimeout(r, 1000))

    // Query answer_logs for this KB
    const result = runPsql(
      `SELECT llm_model FROM answer_logs 
       WHERE ${kbId} = ANY(kb_ids) 
       AND query = 'Spring Boot 默认端口'
       ORDER BY created_at DESC 
       LIMIT 1`
    )

    console.log('Answer log query result:', result)

    // Assert: Should use qwen-plus
    expect(result).toContain('qwen-plus')
    expect(result).not.toContain('qwen-max')
  })

  test('API response should indicate qwen-plus as the model used', async ({ request }) => {
    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: 'Spring Boot 热部署配置',
        stream: false,
      },
    })

    expect(response.ok()).toBeTruthy()
    const body = await response.json()

    const llmModel = body.data?.llmModel || body.llmModel

    console.log('LLM model in response:', llmModel)

    // Assert: Should be qwen-plus
    expect(llmModel).toBe('qwen-plus')
  })

  test('latency.llm should be recorded and greater than 0', async ({ request }) => {
    const startTime = Date.now()

    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: 'Spring Boot 微服务架构',
        stream: false,
      },
    })

    const totalTime = Date.now() - startTime
    expect(response.ok()).toBeTruthy()
    const body = await response.json()

    const llmLatency = body.data?.latency?.llm || body.latency?.llm
    const totalLatency = body.data?.latency?.total || body.latency?.total

    console.log('LLM latency:', llmLatency, 'ms')
    console.log('Total latency:', totalLatency, 'ms')
    console.log('Measured total time:', totalTime, 'ms')

    // Assert: LLM latency should be > 0
    expect(llmLatency).toBeGreaterThan(0)

    // Assert: Total latency should include LLM time
    expect(totalLatency).toBeGreaterThanOrEqual(llmLatency)
  })

  test('explicit answer_model should override default', async ({ request }) => {
    // Set explicit model (even if not actually different, test the override mechanism)
    runPsql(`UPDATE knowledge_bases SET answer_model = 'qwen-turbo' WHERE id = ${kbId}`)
    await new Promise((r) => setTimeout(r, 500))

    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: 'Spring Boot 定时任务',
        stream: false,
      },
    })

    expect(response.ok()).toBeTruthy()
    const body = await response.json()

    const llmModel = body.data?.llmModel || body.llmModel

    console.log('LLM model with explicit setting:', llmModel)

    // Assert: Should use the explicitly set model
    expect(llmModel).toBe('qwen-turbo')
  })

  test('multiple KBs should inherit default model from first KB', async ({ request }) => {
    // Create second KB
    const kbId2 = await createKb(request, headers, 't11-acc-07-second-kb')
    runPsql(`UPDATE knowledge_bases SET answer_model = NULL WHERE id = ${kbId2}`)

    const docId2 = await uploadFile(request, headers, kbId2, asset('answer-kb-numerical.txt'))
    await waitForStatus(request, headers, docId2, 'COMPLETED')

    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId, kbId2],
        query: 'Spring Boot 和营收',
        stream: false,
      },
    })

    expect(response.ok()).toBeTruthy()
    const body = await response.json()

    const llmModel = body.data?.llmModel || body.llmModel

    console.log('LLM model with multiple KBs:', llmModel)

    // Should use default qwen-plus since first KB has no explicit model
    expect(llmModel).toBe('qwen-plus')

    // Cleanup
    await cleanupKb(request, headers, kbId2)
  })
})
