/**
 * T11 ACC-01: SSE 真流式 token-by-token 推送验证
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
  waitForAnswerComplete,
  postAnswerSse,
  asset,
} from './_helpers/t11-common'

test.describe('T11 ACC-01 Streaming Token-by-Token', () => {
  let kbId: number
  let headers: Record<string, string>

  test.beforeAll(async ({ request }) => {
    headers = await login(request)
  })

  test.beforeEach(async ({ request }) => {
    kbId = await createKb(request, headers, 't11-acc-01-streaming', 'ON')
    if (process.env.RAGFORGE_T11_SINGLE_KB === '1') {
      return
    }
    const docId = await uploadFile(request, headers, kbId, asset('answer-kb-tech-faq.txt'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
  })

  test.afterEach(async ({ request }) => {
    await cleanupKb(request, headers, kbId)
  })

  test('SSE must stream tokens one by one, not all at once', async ({ page, request }) => {
    test.setTimeout(180_000)
    const query = 'Spring Boot 3.5 默认端口是多少'

    const apiSse = await postAnswerSse(request, headers, {
      kbIds: [kbId],
      query,
      answerMode: 'ON',
      topK: 10,
      maxTokens: 800,
    })
    const tokenEvents = apiSse.events.filter((e) => e.event === 'token')
    expect(tokenEvents.length, 'SSE must emit multiple token events').toBeGreaterThanOrEqual(5)
    expect(apiSse.complete?.guardRailResult).toBe('PASS')
    expect(apiSse.complete?.answer || '').toContain('8080')

    await openAnswerPlayground(page, headers)
    await selectKb(page, kbId)
    await submitAnswer(page, query)
    await waitForAnswerComplete(page)

    const finalAnswer = await getAnswerText(page)
    expect(finalAnswer).toContain('8080')
    const sseEventLines = await page.locator('.events .event-line').count()
    expect(sseEventLines).toBeGreaterThanOrEqual(3)
    expect(await page.locator('.error-box').isVisible().catch(() => false)).toBe(false)
    expect(await page.locator('.citation-card').count()).toBeGreaterThanOrEqual(1)

    await page.screenshot({ path: 'test-results/v5-acceptance/t11/t11-acc-01-final.png' })
  })

  test('SSE events should include retrieval before tokens', async ({ page, request }) => {
    test.setTimeout(180_000)
    const apiSse = await postAnswerSse(request, headers, {
      kbIds: [kbId],
      query: 'Spring Boot 默认端口',
      answerMode: 'ON',
      topK: 10,
      maxTokens: 800,
    })
    const retrievalIdx = apiSse.events.findIndex((e) => e.event === 'retrieval')
    const firstTokenIdx = apiSse.events.findIndex((e) => e.event === 'token')
    expect(retrievalIdx).toBeGreaterThanOrEqual(0)
    expect(firstTokenIdx).toBeGreaterThan(retrievalIdx)

    await openAnswerPlayground(page, headers)
    await selectKb(page, kbId)
    await submitAnswer(page, 'Spring Boot 默认端口')
    await waitForAnswerComplete(page)
    expect(await getAnswerText(page)).toContain('8080')
  })
})
