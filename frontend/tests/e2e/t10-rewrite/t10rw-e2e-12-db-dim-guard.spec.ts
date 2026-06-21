import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunkRaw, login, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-12 @e2e-dashscope-real raw DB diagnostic reports strict 2560 dimensions', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-dim-guard', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('text_only.pdf'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const raw = await getChunkRaw(request, headers, docId)
    expect(raw.length).toBeGreaterThan(0)
    expect(raw.every((c) => c.vlVectorDim === 2560)).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
