import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  deleteCleanProfile,
  getStatus,
  login,
  loginPage,
  parseCleanReport,
  getDocument,
  uploadFile,
  waitForTerminalStatus,
  cleanupKb,
  screenshotPair,
} from '../_helpers/t8-common'
import { T8_FIXTURES } from './fixtures/t8-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-09 empty after strip fallback', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-09-empty')
  try {
    await createCleanProfile(request, headers, kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'MASK',
    })

    const docId = await uploadFile(request, headers, kbId, asset(T8_FIXTURES.emptyAfterStrip))
    await waitForTerminalStatus(request, headers, docId, 240_000)

    const status = await getStatus(request, headers, docId)
    const upperStatus = String(status.parseStatus || '').toUpperCase()
    expect(['COMPLETED', 'FAILED']).toContain(upperStatus)

    if (upperStatus === 'FAILED') {
      expect(status.errorMsg || '').toMatch(/EMPTY_AFTER_CLEAN|EMPTY|NO_CHUNK/)
    } else {
      expect(status.chunkCount).toBeGreaterThan(0)
      const detail = parseCleanReport(await getDocument(request, headers, docId))
      expect(detail?.cleanedLength).toBeLessThan((detail?.originalLength || 0) + 1)
    }

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '09')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(request, headers, kbId)
  }
})
