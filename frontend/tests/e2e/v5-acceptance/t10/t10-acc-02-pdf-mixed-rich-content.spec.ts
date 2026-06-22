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
  screenshotDocumentDetail,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { expectOcrContains, expectVlVectorDim, modalityCounts, ocrSimilarity } from '../_helpers/t10-asserts'
import { OCR_CHART_TOKENS, T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 300_000 })

test('T10 ACC-02 @e2e-dashscope-real mixed rich PDF yields TEXT + IMAGE chunks in vl space', async ({
  page,
  request,
}, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-02', 'ON')
  try {
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.pdfMixedRich))
    await waitForStatus(request, headers, docId, 'COMPLETED', 300_000)

    const doc = await getDocument(request, headers, docId)
    const chunks = await getChunks(request, headers, docId)
    const raw = await getChunkRaw(request, headers, docId)
    const counts = modalityCounts(raw)

    expect(doc.chunkCount).toBeGreaterThanOrEqual(10)
    expect(chunks.length).toBeGreaterThanOrEqual(10)
    expect(counts.TEXT || 0).toBeGreaterThan(0)
    expect(counts.IMAGE || 0).toBeGreaterThan(0)
    expectVlVectorDim(raw)

    const imageWithOcr = chunks.filter((c) => c.chunkModality === 'IMAGE')
    expect(imageWithOcr.length).toBeGreaterThan(0)
    expect(
      imageWithOcr.some((chunk) => ocrSimilarity(chunk.content || '', OCR_CHART_TOKENS, 0.66) >= 0.66),
    ).toBeTruthy()

    await loginPage(page)
    const screenshotPath = await screenshotDocumentDetail(page, testInfo, docId, kbId, '02-mixed-rich')
    testInfo.annotations.push({ type: 'headed-artifact', description: screenshotPath })
    await expect(page.locator('.chunk-modality').filter({ hasText: 'TEXT' }).first()).toBeVisible()
    await expect(page.locator('.chunk-modality').filter({ hasText: 'IMAGE' }).first()).toBeVisible()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
