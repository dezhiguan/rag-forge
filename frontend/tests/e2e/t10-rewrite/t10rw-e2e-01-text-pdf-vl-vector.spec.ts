import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunkRaw, getChunks, login, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-1 @e2e-dashscope-real text PDF writes TEXT chunks with 2560-dim vl_vector', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-text-pdf', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('text_only.pdf'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const chunks = await getChunks(request, headers, docId)
    const raw = await getChunkRaw(request, headers, docId)
    expect(chunks.length).toBeGreaterThanOrEqual(3)
    expect(raw.every((c) => c.modality === 'TEXT')).toBeTruthy()
    expect(raw.every((c) => c.vlVectorDim === 2560)).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
