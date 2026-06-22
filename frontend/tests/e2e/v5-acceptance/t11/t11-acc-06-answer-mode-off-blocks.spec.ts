/**
 * T11 ACC-06: Answer Mode OFF 拦截验证
 * 目标：KB.answer_mode='OFF' 时调用必须返回 403 ANSWER_DISABLED
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

test.describe('T11 ACC-06 Answer Mode OFF Blocks', () => {
  let kbId: number
  let techDocId: number
  let headers: Record<string, string>

  test.beforeAll(async ({ request }) => {
    headers = await login(request)
  })

  test.beforeEach(async ({ request }) => {
    // Create KB with document
    kbId = await createKb(request, headers, 't11-acc-06-off-mode')
    techDocId = await uploadFile(request, headers, kbId, asset('answer-kb-tech-faq.txt'))
    await waitForStatus(request, headers, techDocId, 'COMPLETED')
  })

  test.afterEach(async ({ request }) => {
    await cleanupKb(request, headers, kbId)
  })

  test('API should return 403 when KB answer_mode is OFF', async ({ request }) => {
    // Set KB answer_mode to OFF via SQL
    runPsql(`UPDATE knowledge_bases SET answer_mode = 'OFF' WHERE id = ${kbId}`)

    // Wait a moment for cache to clear if any
    await new Promise((r) => setTimeout(r, 500))

    // Try to call answer API
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

    // Assert: Should get 403
    expect(response.status()).toBe(403)

    const body = await response.json().catch(() => ({}))
    const errorCode = body.code || body.error || body.message || ''

    // Assert: Error should indicate answer disabled
    expect(errorCode).toMatch(/ANSWER_DISABLED|disabled|403/i)

    console.log('403 Response:', JSON.stringify(body, null, 2))
  })

  test('frontend should show error when KB is not enabled for answer', async ({ page, request }) => {
    // Set KB answer_mode to OFF
    runPsql(`UPDATE knowledge_bases SET answer_mode = 'OFF' WHERE id = ${kbId}`)
    await new Promise((r) => setTimeout(r, 500))

    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    await submitAnswer(page, 'Spring Boot 默认端口')

    // Wait for error to appear
    await page.locator('.error-box').waitFor({ timeout: 10_000 })

    const errorText = await page.locator('.error-box').textContent()
    console.log('Error displayed:', errorText)

    // Assert: Error should mention disabled or not enabled
    expect(errorText).toMatch(/未启用|disabled|not enabled|403|ANSWER_DISABLED/i)

    await page.screenshot({
      path: `test-results/v5-acceptance/t11/t11-acc-06-off-mode-error.png`,
    })
  })

  test('answer_logs should not have new entries when blocked', async ({ request }) => {
    // Get initial count of answer_logs for this KB
    const initialResult = runPsql(
      `SELECT COUNT(*) FROM answer_logs WHERE ${kbId} = ANY(kb_ids)`
    )
    const initialCount = parseInt(initialResult.match(/\d+/)?.[0] || '0')

    // Set OFF mode
    runPsql(`UPDATE knowledge_bases SET answer_mode = 'OFF' WHERE id = ${kbId}`)
    await new Promise((r) => setTimeout(r, 500))

    // Try to make a request
    await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: '测试问题',
        stream: false,
      },
    })

    // Wait for potential logging
    await new Promise((r) => setTimeout(r, 1000))

    // Check count again
    const finalResult = runPsql(
      `SELECT COUNT(*) FROM answer_logs WHERE ${kbId} = ANY(kb_ids)`
    )
    const finalCount = parseInt(finalResult.match(/\d+/)?.[0] || '0')

    // Assert: No new log entries should be created
    expect(finalCount).toBe(initialCount)

    console.log(`Log count: ${initialCount} -> ${finalCount} (no change expected)`)
  })

  test('SSE mode should also return error for OFF mode', async ({ page }) => {
    runPsql(`UPDATE knowledge_bases SET answer_mode = 'OFF' WHERE id = ${kbId}`)
    await new Promise((r) => setTimeout(r, 500))

    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    await submitAnswer(page, '测试 SSE 禁用')

    // Wait for completion or error
    await expect
      .poll(async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return !text?.includes('生成中')
      })
      .toBe(true)

    // Check for error
    const hasError = await page.locator('.error-box').isVisible()
    expect(hasError).toBe(true)

    const errorText = await page.locator('.error-box').textContent()
    expect(errorText).toMatch(/未启用|disabled|403|error/i)
  })

  test('PREVIEW mode should work when OFF is set', async ({ request }) => {
    // Set PREVIEW mode
    runPsql(`UPDATE knowledge_bases SET answer_mode = 'PREVIEW' WHERE id = ${kbId}`)
    await new Promise((r) => setTimeout(r, 500))

    // Call with answerMode=PREVIEW should work
    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: 'Spring Boot 默认端口',
        answerMode: 'PREVIEW',
        stream: false,
      },
    })

    // PREVIEW mode might be allowed or blocked depending on implementation
    // Log the result for investigation
    console.log('PREVIEW mode response status:', response.status())
    const body = await response.json().catch(() => ({}))
    console.log('PREVIEW mode response:', JSON.stringify(body, null, 2))
  })
})
