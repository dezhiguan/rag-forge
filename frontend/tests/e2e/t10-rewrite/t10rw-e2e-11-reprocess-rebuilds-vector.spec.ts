import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunkRaw, login, reprocess, setDocumentStatus, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-11 @e2e-dashscope-real reprocess rebuilds chunks with 2560-dim vl_vector', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-reprocess', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('image_with_text.png'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const before = await getChunkRaw(request, headers, docId)
    await setDocumentStatus(request, headers, docId, 'FAILED')
    await reprocess(request, headers, docId)
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const after = await getChunkRaw(request, headers, docId)
    expect(after.every((c) => c.vlVectorDim === 2560)).toBeTruthy()
    expect(after.map((c) => c.chunkId)).not.toEqual(before.map((c) => c.chunkId))
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
