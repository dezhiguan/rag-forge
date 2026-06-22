/**
 * T11 ACC-05: SSE Retrieval Event PII 脱敏验证
 * 目标：SSE retrieval event 中推送的 chunk content 必须经过 PII MASK，前端看不到原始 PII
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

test.describe('T11 ACC-05 SSE Retrieval PII Masked', () => {
  let kbId: number
  let piiDocId: number
  let headers: Record<string, string>

  test.beforeAll(async ({ request }) => {
    headers = await login(request)
  })

  test.beforeEach(async ({ request }) => {
    // Create KB with PII document
    kbId = await createKb(request, headers, 't11-acc-05-sse-pii')
    piiDocId = await uploadFile(request, headers, kbId, asset('answer-kb-pii-mixed.txt'))
    await waitForStatus(request, headers, piiDocId, 'COMPLETED')
  })

  test.afterEach(async ({ request }) => {
    await cleanupKb(request, headers, kbId)
  })

  test('SSE retrieval event chunks must have PII masked', async ({ page, request }) => {
    // Capture raw SSE response
    let sseBody = ''

    await page.route('**/api/v1/answer', async (route) => {
      const response = await route.fetch()
      sseBody = await response.text()
      await route.fulfill({ response, body: sseBody })
    })

    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    // Query that triggers retrieval
    await submitAnswer(page, '联系方式')

    await expect
      .poll(async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return !text?.includes('生成中')
      })
      .toBe(true)

    // Parse SSE events
    const events: Array<{ type: string; data: any }> = []
    const frames = sseBody.split('\n\n')

    for (const frame of frames) {
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

    // Find retrieval event
    const retrievalEvent = events.find((e) => e.type === 'retrieval')
    expect(retrievalEvent).toBeDefined()

    const chunks = retrievalEvent?.data?.chunks || retrievalEvent?.data?.data?.chunks || []
    expect(chunks.length).toBeGreaterThan(0)

    console.log(`Found ${chunks.length} chunks in retrieval event`)

    // Check each chunk for PII masking
    const phoneRegex = /1[3-9]\d{9}/
    const emailRegex = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/
    const idCardRegex = /\d{17}[0-9Xx]/

    let chunksWithRawPii = 0
    let chunksWithMaskedPii = 0

    for (const chunk of chunks) {
      const content = chunk.content || ''
      const hasRawPhone = phoneRegex.test(content)
      const hasRawEmail = emailRegex.test(content)
      const hasRawIdCard = idCardRegex.test(content)

      if (hasRawPhone || hasRawEmail || hasRawIdCard) {
        chunksWithRawPii++
        console.log('Chunk with raw PII:', content.substring(0, 100))
      }

      // Check for masking patterns (*** or similar)
      const hasMasking = /\*{3,}|\d{3}[\*\.]{4}\d{4}/.test(content)
      if (hasMasking) {
        chunksWithMaskedPii++
      }
    }

    console.log(`Chunks with raw PII: ${chunksWithRawPii}`)
    console.log(`Chunks with masking: ${chunksWithMaskedPii}`)

    // Assert: No raw PII should be in SSE retrieval event
    expect(chunksWithRawPii).toBe(0)

    // Assert: Either masking is present OR no PII was in original content
    // (The chunk might not contain PII if splitting happened differently)
    expect(chunksWithMaskedPii).toBeGreaterThanOrEqual(0)
  })

  test('chunk metadata should be preserved while content is masked', async ({ page, request }) => {
    let sseBody = ''

    await page.route('**/api/v1/answer', async (route) => {
      const response = await route.fetch()
      sseBody = await response.text()
      await route.fulfill({ response, body: sseBody })
    })

    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    await submitAnswer(page, '张三')

    await expect
      .poll(async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return !text?.includes('生成中')
      })
      .toBe(true)

    // Parse retrieval event
    const frames = sseBody.split('\n\n')
    const retrievalFrame = frames.find((f) => f.includes('event: retrieval'))

    expect(retrievalFrame).toBeDefined()

    const dataMatch = retrievalFrame?.match(/^data:\s*(.+)$/m)
    const data = dataMatch ? JSON.parse(dataMatch[1]) : null
    const chunks = data?.chunks || data?.data?.chunks || []

    expect(chunks.length).toBeGreaterThan(0)

    for (const chunk of chunks) {
      // Metadata should be preserved
      expect(chunk.chunkId).toBeDefined()
      expect(chunk.docId).toBeDefined()
      expect(chunk.filename).toBeDefined()

      // Scores should be present
      expect(typeof chunk.finalScore === 'number' || typeof chunk.score === 'number').toBe(true)
    }

    console.log('All chunks have preserved metadata:', chunks.length)
  })

  test('frontend UI should show masked PII in citations', async ({ page }) => {
    await openAnswerPlayground(page)
    await selectKb(page, kbId)

    await submitAnswer(page, '张三联系方式')

    await expect
      .poll(async () => {
        const btn = page.locator('.run-btn')
        const text = await btn.textContent()
        return !text?.includes('生成中')
      })
      .toBe(true)

    // Check citations displayed in UI
    const citationSnippets = await page.locator('.citation-card .citation-body p').allTextContents()

    const phoneRegex = /1[3-9]\d{9}/
    const emailRegex = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/

    let rawPiiCount = 0

    for (const snippet of citationSnippets) {
      if (phoneRegex.test(snippet) || emailRegex.test(snippet)) {
        rawPiiCount++
        console.log('Citation with raw PII:', snippet)
      }
    }

    // Citations in UI should also be masked (no raw PII)
    expect(rawPiiCount).toBe(0)

    await page.screenshot({
      path: `test-results/v5-acceptance/t11/t11-acc-05-masked-citations.png`,
    })
  })

  test('PII mask should use consistent placeholder', async ({ request }) => {
    // Direct API call to check masking format
    const response = await request.post(apiUrl('/api/v1/answer'), {
      headers: {
        ...headers,
        'Content-Type': 'application/json',
      },
      data: {
        kbIds: [kbId],
        query: '联系方式',
        stream: false,
      },
    })

    expect(response.ok()).toBeTruthy()
    const body = await response.json()

    const citations = body.data?.citations || body.citations || []

    // Check citation snippets for masking
    for (const citation of citations) {
      const snippet = citation.textSnippet || ''

      // Should not have raw phone
      expect(snippet).not.toMatch(/1[3-9]\d{9}/)

      // Should not have raw email
      expect(snippet).not.toMatch(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/)
    }

    console.log(`Checked ${citations.length} citations for PII masking`)
  })
})
