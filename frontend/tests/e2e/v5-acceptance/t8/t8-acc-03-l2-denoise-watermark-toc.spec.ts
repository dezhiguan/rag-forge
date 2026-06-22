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
import { T8_FIXTURES } from './fixtures/t8-fixtures'
import { reasonCount } from '../_helpers/t8-asserts'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-03 L2 denoise watermark and toc', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-03-l2')
  try {
    await createCleanProfile(kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: false,
      piiPolicy: 'MASK',
    })

    const docId = await uploadFile(request, headers, kbId, asset(T8_FIXTURES.toc))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)

    const detail = await getDocument(request, headers, docId)
    const chunks = await getChunks(request, headers, docId)
    const chunkText = chunks.map((chunk) => String(chunk.content || '')).join('\n')

    expect(chunkText).not.toContain('保密 内部资料')
    expect(chunkText).not.toContain('目录')
    expect(chunkText).not.toMatch(/.{1,90}(?:\.{3,}|…{2,})\s*\d+\s*$/m)

    const report = parseCleanReport(detail)
    expect(reasonCount(report, 'WATERMARK')).toBeGreaterThan(0)
    expect(reasonCount(report, 'TOC')).toBeGreaterThan(0)

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '03')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(kbId)
  }
})
