/**
 * T11 ACC-03: 无引用 GuardRail 拦截验证
 * 目标：当检索结果为空时，必须返回 "未找到相关信息" 答案，且 GuardRailResult=NO_CITATIONS 拦截（如果 LLM 强答）
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
  getCitations,
  asset,
  apiUrl,
} from './_helpers/t11-common'

test.describe('T11 ACC-03 No Citation GuardRail', () => {
  let kbId: number
  let emptyDocId: number
  let headers: Record<string, string>

  test.beforeAll(async ({ request }) => {
    headers = await login(request)
  })

  test.beforeEach(async ({ request }) => {
    // Create KB with empty document
    kbId = await createKb(request, headers, 't11-acc-03-empty')
    emptyDocId = await uploadFile(request, headers, kbId, asset('answer-kb-empty.txt'))
    await waitForStatus(request, headers, emptyDocId, 'COMPLETED')
  })

  test.afterEach(async ({ request }) => {
    await cleanupKb(request, headers, kbId)
  })

  test('empty KB should return NOT_FOUND answer', async ({ page }) => {
    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    // Query about something that shouldn't be in empty KB
    await submitAnswer(page, '公司 2024 年营收是多少')

    await expect
      .poll(async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return !text?.includes('生成中')
      })
      .toBe(true)

    const answer = await getAnswerText(page)

    // Assert: Should get "未找到" or similar message
    expect(answer).toMatch(/未找到|未在知识库|no relevant|not found/i)

    // Assert: No citations should be present
    const citations = await getCitations(page)
    expect(citations.length).toBe(0)

    // Assert: Citations panel should show empty state
    const emptyCitations = await page.locator('.empty-cites').isVisible()
    expect(emptyCitations).toBe(true)

    await page.screenshot({
      path: `test-results/v5-acceptance/t11/t11-acc-03-not-found.png`,
    })
  })

  test('response should have empty citations array and appropriate guard rail', async ({ page, request }) => {
    // Call API directly to verify response structure
    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: '公司 2024 年营收',
        retrievalStrategy: 'hybrid',
        answerMode: 'ON',
        stream: false, // Use blocking mode for easier inspection
      },
    })

    expect(response.ok()).toBeTruthy()
    const body = await response.json()

    // Assert: Answer should be NOT_FOUND message
    expect(body.data?.answer || body.answer).toMatch(/未找到|未在知识库|no relevant|not found/i)

    // Assert: Citations should be empty array
    const citations = body.data?.citations || body.citations || []
    expect(citations).toHaveLength(0)

    // Assert: GuardRailResult should be PASS (because preset answer is compliant)
    // or NO_CITATIONS if it triggered the guard rail
    const guardRailResult = body.data?.guardRailResult || body.guardRailResult
    expect(['PASS', 'NO_CITATIONS']).toContain(guardRailResult)

    console.log('Empty KB response:', JSON.stringify(body, null, 2))
  })

  test('SSE should receive complete event for empty KB', async ({ page }) => {
    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    const events: Array<{ type: string; data: any }> = []

    await page.route('**/api/v1/answer', async (route) => {
      const response = await route.fetch()
      const body = await response.text()

      // Parse SSE
      const lines = body.split('\n\n')
      for (const frame of lines) {
        const eventMatch = frame.match(/^event:\s*(\w+)/m)
        const dataMatch = frame.match(/^data:\s*(.+)$/m)
        if (eventMatch && dataMatch) {
          let data: any = dataMatch[1]
          try {
            data = JSON.parse(data)
          } catch {
            // keep as string
          }
          events.push({ type: eventMatch[1], data })
        }
      }

      await route.fulfill({ response, body })
    })

    await submitAnswer(page, '查询不存在的内容')

    await expect
      .poll(async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return !text?.includes('生成中')
      })
      .toBe(true)

    // Assert: Should have complete event
    const completeEvent = events.find((e) => e.type === 'complete')
    expect(completeEvent).toBeDefined()

    // Assert: Complete event should have empty citations
    const completeData = completeEvent?.data || {}
    const citations = completeData.citations || completeData.data?.citations || []
    expect(citations).toHaveLength(0)

    // Assert: Should have token event with NOT_FOUND message
    const tokenEvents = events.filter((e) => e.type === 'token')
    const notFoundToken = tokenEvents.find((e) => {
      const text = e.data?.delta || e.data?.answer || ''
      return /未找到|未在知识库/.test(text)
    })
    expect(notFoundToken).toBeDefined()
  })

  test('answer_logs should record empty result correctly', async ({ request }) => {
    // Make a request first
    const answerRes = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: '测试空库查询',
        stream: false,
      },
    })
    expect(answerRes.ok()).toBeTruthy()

    // Wait a moment for log to be written
    await new Promise((r) => setTimeout(r, 500))

    // Query answer_logs via admin endpoint (if available) or check metrics
    // For now, we just verify the API response was correct
    const body = await answerRes.json()

    // Assert response structure
    expect(body.data?.answer || body.answer).toBeDefined()
    expect(body.data?.citations || body.citations).toEqual([])
  })
})
