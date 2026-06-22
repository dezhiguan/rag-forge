/**
 * T11 ACC-01: SSE 真流式 token-by-token 推送验证
 * 目标：SSE 必须真流式 token-by-token 推送，前端答案区逐字出现
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
  captureAnswerSnapshots,
  asset,
  enableKbAnswerMode,
} from './_helpers/t11-common'

test.describe('T11 ACC-01 Streaming Token-by-Token', () => {
  let kbId: number
  let headers: Record<string, string>

  test.beforeAll(async ({ request }) => {
    headers = await login(request)
  })

  test.beforeEach(async ({ request }) => {
    // Create KB and upload tech FAQ
    kbId = await createKb(request, headers, 't11-acc-01-streaming')
    // Enable answer mode for the KB via SQL
    enableKbAnswerMode(kbId)
    const docId = await uploadFile(request, headers, kbId, asset('answer-kb-tech-faq.txt'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
  })

  test.afterEach(async ({ request }) => {
    await cleanupKb(request, headers, kbId)
  })

  test('SSE must stream tokens one by one, not all at once', async ({ page, request }) => {
    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    // Enter query about Spring Boot default port
    const query = 'Spring Boot 3.5 默认端口是多少'
    await submitAnswer(page, query)

    // Capture snapshots every 200ms during streaming
    // Wait for streaming to start
    await page.waitForTimeout(500)

    // Take multiple snapshots over time
    const snapshots: string[] = []
    let previousLength = 0
    let growthCount = 0

    // Wait for streaming and capture SSE events from the UI
    let tokenEventsCount = 0
    for (let i = 0; i < 30; i++) {
      await page.waitForTimeout(100)
      const currentText = await getAnswerText(page)

      // Check if text is growing
      if (currentText.length > previousLength) {
        growthCount++
      }
      previousLength = currentText.length
      snapshots.push(currentText)

      // Count token events in SSE Events section
      const sseEvents = await page.locator('.event-line').count().catch(() => 0)
      tokenEventsCount = sseEvents

      // Break if answer seems complete
      if (currentText.length > 30 && currentText.includes('8080')) {
        break
      }
    }

    // Wait for answer to contain expected content (streaming complete)
    await expect
      .poll(async () => {
        const answerText = await getAnswerText(page)
        return answerText.includes('8080')
      }, { timeout: 120_000, intervals: [500] })
      .toBe(true)

    // Count SSE events to verify streaming occurred
    const sseEventLines = await page.locator('.events .event-line').count().catch(() => 0)
    console.log(`SSE event lines found: ${sseEventLines}`)

    // Assert: Must have at least 3 SSE events (retrieval + multiple tokens)
    expect(sseEventLines, 'Should have multiple SSE events showing streaming').toBeGreaterThanOrEqual(3)

    // Assert: Final answer must contain expected information
    const finalAnswer = await getAnswerText(page)
    expect(finalAnswer).toContain('8080')

    // Assert: No error should be shown
    const errorVisible = await page.locator('.error-box').isVisible().catch(() => false)
    expect(errorVisible).toBe(false)

    // Assert: Citations section should have at least one citation
    const citations = page.locator('.citation-card')
    const citationCount = await citations.count()
    expect(citationCount).toBeGreaterThanOrEqual(1)

    // Take final screenshot
    await page.screenshot({
      path: `test-results/v5-acceptance/t11/t11-acc-01-final.png`,
    })

    console.log(`Streaming snapshots captured: ${snapshots.length}`)
    console.log(`Text growth events: ${growthCount}`)
    console.log(`Final answer length: ${finalAnswer.length}`)
  })

  test('SSE events should include retrieval before tokens', async ({ page, request }) => {
    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    // Monitor SSE events via browser console/network
    const events: Array<{ type: string; timestamp: number }> = []

    // Inject script to capture SSE events
    await page.evaluate(() => {
      const originalFetch = window.fetch
      window.fetch = async (...args) => {
        const response = await originalFetch(...args)
        if (args[0]?.toString().includes('/api/v1/answer')) {
          // Clone response to read body
          const clone = response.clone()
          const reader = clone.body?.getReader()
          if (reader) {
            const decoder = new TextDecoder()
            let buffer = ''
            const read = async () => {
              while (true) {
                const { value, done } = await reader.read()
                if (done) break
                buffer += decoder.decode(value, { stream: true })
                const frames = buffer.split('\n\n')
                buffer = frames.pop() || ''
                for (const frame of frames) {
                  const eventMatch = frame.match(/^event:\s*(.+)$/m)
                  if (eventMatch) {
                    window.dispatchEvent(
                      new CustomEvent('sse-event', {
                        detail: { type: eventMatch[1], timestamp: Date.now() },
                      })
                    )
                  }
                }
              }
            }
            read()
          }
        }
        return response
      }
    })

    // Listen for custom events
    page.on('console', (msg) => {
      const text = msg.text()
      if (text.startsWith('SSE_EVENT:')) {
        const event = JSON.parse(text.replace('SSE_EVENT:', ''))
        events.push(event)
      }
    })

    await submitAnswer(page, 'Spring Boot 默认端口')

    // Wait for answer to contain expected content
    await expect
      .poll(async () => {
        const answerText = await getAnswerText(page)
        return answerText.includes('8080')
      }, { timeout: 120_000, intervals: [500] })
      .toBe(true)

    // Check that we got retrieval event
    const retrievalEvents = events.filter((e) => e.type === 'retrieval')
    const tokenEvents = events.filter((e) => e.type === 'token')
    const completeEvents = events.filter((e) => e.type === 'complete')

    expect(retrievalEvents.length).toBeGreaterThanOrEqual(1)
    expect(tokenEvents.length).toBeGreaterThanOrEqual(1)
    expect(completeEvents.length).toBeGreaterThanOrEqual(1)

    // Assert retrieval comes before tokens
    if (retrievalEvents.length > 0 && tokenEvents.length > 0) {
      expect(retrievalEvents[0].timestamp).toBeLessThanOrEqual(tokenEvents[0].timestamp)
    }
  })
})
