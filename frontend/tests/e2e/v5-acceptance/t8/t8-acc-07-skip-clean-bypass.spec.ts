import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  deleteCleanProfile,
  getDocument,
  getChunks,
  login,
  loginPage,
  parseCleanReport,
  uploadFile,
  waitForStatus,
  cleanupKb,
  screenshotPair,
} from '../_helpers/t8-common'
import { fixtureText as readFixtureText } from './fixtures/t8-fixtures'
import { T8_FIXTURES } from './fixtures/t8-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-07 Skip clean bypass', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-07-skip')
  try {
    await createCleanProfile(kbId, {
      skipClean: true,
      l1Enabled: false,
      l2Enabled: false,
      l3Enabled: false,
      piiPolicy: 'MASK',
    })

    const docId = await uploadFile(request, headers, kbId, asset(T8_FIXTURES.mixed))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)

    const detail = await getDocument(request, headers, docId)
    const report = parseCleanReport(detail)
    const chunks = await getChunks(request, headers, docId)
    const text = chunks.map((chunk) => String(chunk.content || '')).join('\n')
    const source = readFixtureText(T8_FIXTURES.mixed)

    expect(text).toContain('13812345678')
    expect(text).toContain('alice@example.com')
    expect(text).toContain('广州市某科技公司')
    expect((report && report.profile?.skipClean) === true || Object.keys(report || {}).length === 0).toBeTruthy()

    const originalLen = source.length
    const cleanedLen = report?.cleanedLength || text.length
    const diff = Math.abs(cleanedLen - originalLen)
    expect(diff).toBeLessThanOrEqual(Math.max(1, Math.ceil(originalLen * 0.05)))

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '07')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(kbId)
  }
})
