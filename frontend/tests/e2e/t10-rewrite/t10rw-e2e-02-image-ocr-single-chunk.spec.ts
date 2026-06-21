import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunkRaw, getChunks, login, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-2 @e2e-dashscope-real image upload creates one IMAGE chunk with OCR text', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-image-ocr', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('image_with_text.png'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const chunks = await getChunks(request, headers, docId)
    const raw = await getChunkRaw(request, headers, docId)
    expect(chunks).toHaveLength(1)
    expect(raw[0].modality).toBe('IMAGE')
    expect(chunks[0].content || '').toMatch(/RAGForge|架构图/)
    expect(raw[0].vlVectorDim).toBe(2560)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
