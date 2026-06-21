import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunks, login, searchByText, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-6 @e2e-dashscope-real vector text query recalls no-text image via unified VL space', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-no-text-image', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('image_no_text.png'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const chunks = await getChunks(request, headers, docId)
    expect((chunks[0].content || '').toLowerCase()).not.toContain('architecture')
    const results = await searchByText(request, headers, kbId, 'system architecture diagram', 'vector')
    expect(results.some((r) => r.docId === docId && r.chunkModality === 'IMAGE')).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
