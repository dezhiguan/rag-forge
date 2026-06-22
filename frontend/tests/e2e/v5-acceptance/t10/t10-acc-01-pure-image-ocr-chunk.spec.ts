import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  getChunkRaw,
  getChunks,
  getDocument,
  login,
  loginPage,
  uploadFile,
  verifyDocumentDownload,
  waitForStatus,
} from '../_helpers/t10-common'
import { expectOcrContains, expectVlVectorDim } from '../_helpers/t10-asserts'
import { OCR_REVENUE_TOKENS, T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T10 ACC-01 @e2e-dashscope-real pure image OCR chunk with vl_vector', async ({ page, request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-01', 'OFF')
  try {
    const file = T10_FIXTURES.pureImageWithText
    const docId = await uploadFile(request, headers, kbId, asset(file))
    await waitForStatus(request, headers, docId, 'COMPLETED')

    const doc = await getDocument(request, headers, docId)
    const chunks = await getChunks(request, headers, docId)
    const raw = await getChunkRaw(request, headers, docId)

    expect(doc.chunkCount).toBe(1)
    expect(chunks).toHaveLength(1)
    expect(chunks[0].chunkModality).toBe('IMAGE')
    expectOcrContains(chunks[0].content || '', OCR_REVENUE_TOKENS)
    expectVlVectorDim(raw)

    await verifyDocumentDownload(request, headers, docId)

    await loginPage(page)
    await page.goto(`/document/${docId}`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.chunk-modality').filter({ hasText: 'IMAGE' })).toBeVisible()
    await expect(page.locator('.chunk-thumb')).toBeVisible()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
