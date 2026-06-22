import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  deleteCleanProfile,
  getChunks,
  getDocument,
  login,
  loginPage,
  parseCleanReport,
  uploadFile,
  waitForStatus,
  cleanupKb,
  screenshotPair,
} from '../_helpers/t8-common'
import { T8_FIXTURES, fixtureText } from './fixtures/t8-fixtures'
import { countRemovedRegions, reasonCount } from '../_helpers/t8-asserts'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-02 L2 denoise header footer', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-02-l2')
  try {
    await createCleanProfile(kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: false,
      piiPolicy: 'MASK',
    })

    const file = T8_FIXTURES.noisy
    const fixture = fixtureText(file)
    const docId = await uploadFile(request, headers, kbId, asset(file))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)

    const detail = await getDocument(request, headers, docId)
    const chunks = await getChunks(request, headers, docId)

    const chunkText = chunks.map((chunk) => String(chunk.content || '')).join('\n')
    for (const line of chunkText.split('\n')) {
      expect(line).not.toMatch(/广州市某科技公司[\s\S]*第\s*\d+\s*页/)
    }

    const report = parseCleanReport(detail)
    const pageCount = fixture.split('\f').length
    const repeated = reasonCount(report, 'REPEATED_HEADER_FOOTER')
    expect(repeated).toBeGreaterThanOrEqual(pageCount * 3)

    const regions = report?.removedRegions || []
    expect(regions.every((region) => typeof region.reason === 'string')).toBeTruthy()
    expect(countRemovedRegions(report)).toBeGreaterThanOrEqual(pageCount)

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '02')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(kbId)
  }
})
