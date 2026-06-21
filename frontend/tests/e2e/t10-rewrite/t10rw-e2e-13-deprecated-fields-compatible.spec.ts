import { expect, test } from './fixtures/t10rw-test'
import { apiUrl, asset, cleanupKb, createKb, login, uploadFile, waitForStatus, unwrap } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-13 @e2e-dashscope-real deprecated modality and queryImageBase64 stay soft-compatible', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-deprecated-fields', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('text_only.pdf'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const res = await request.post(apiUrl('/api/v1/search'), {
      headers,
      data: {
        query: 'Spring Boot',
        kbIds: [kbId],
        strategy: 'vector',
        modality: 'image',
        queryImageBase64: 'dummy',
        topK: 3,
      },
    })
    expect(res.status()).toBe(200)
    const body = unwrap(await res.json())
    expect(body.results.length).toBeGreaterThan(0)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
