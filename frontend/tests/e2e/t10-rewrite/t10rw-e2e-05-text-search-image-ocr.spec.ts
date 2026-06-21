import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunks, login, searchByText, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-5 @e2e-dashscope-real hybrid text search hits OCR image chunk', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-ocr-search', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('image_with_text.png'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const chunks = await getChunks(request, headers, docId)
    const ocrChunk = chunks.find((chunk) => /RAGForge|架构图/.test(chunk.content || ''))
    expect(ocrChunk, 'OCR text should be stored in the IMAGE chunk content').toBeTruthy()
    const results = await searchByText(request, headers, kbId, 'RAGForge architecture diagram Spring Boot PostgreSQL', 'vector')
    expect(results.some((r) => r.docId === docId && r.chunkModality === 'IMAGE')).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
