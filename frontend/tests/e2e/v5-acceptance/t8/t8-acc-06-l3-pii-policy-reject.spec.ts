import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  deleteCleanProfile,
  login,
  loginPage,
  parseCleanReport,
  uploadFile,
  waitForTerminalStatus,
  getStatus,
  getDocument,
  cleanupKb,
  screenshotPair,
} from '../_helpers/t8-common'
import { T8_FIXTURES } from './fixtures/t8-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-06 L3 pii policy reject', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-06-reject')
  try {
    await createCleanProfile(request, headers, kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'REJECT',
    })

    const docId = await uploadFile(request, headers, kbId, asset(T8_FIXTURES.pii))
    await waitForTerminalStatus(request, headers, docId, 240_000)
    const status = await getStatus(request, headers, docId)

    expect(status.parseStatus).toBe('FAILED')
    expect(status.errorMsg || '').toMatch(/PII_REJECTED/i)

    const detail = await getDocument(request, headers, docId)
    const report = parseCleanReport(detail)
    expect(report?.cleanedLength ?? 0).toBe(0)
    expect((detail.chunkCount ?? 0)).toBe(0)

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '06')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(request, headers, kbId)
  }
})
