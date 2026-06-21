import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, login, searchByText, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-4 @e2e-dashscope-real vector text search hits text PDF', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-text-search', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('text_only.pdf'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const results = await searchByText(request, headers, kbId, 'Spring Boot PostgreSQL retrieval', 'vector')
    expect(results.length).toBeGreaterThan(0)
    expect(results[0].docId).toBe(docId)
    expect(results[0].chunkModality).toBe('TEXT')
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
