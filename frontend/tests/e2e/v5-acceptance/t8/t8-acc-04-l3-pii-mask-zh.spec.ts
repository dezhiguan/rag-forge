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
import { T8_FIXTURES, PII as PII_FIXTURES } from './fixtures/t8-fixtures'
import { noRawPii, sumPiiHits } from '../_helpers/t8-asserts'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-04 L3 pii mask zh', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-04-l3')
  try {
    await createCleanProfile(kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'MASK',
    })

    const docId = await uploadFile(request, headers, kbId, asset(T8_FIXTURES.pii))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)

    const detail = await getDocument(request, headers, docId)
    const chunks = await getChunks(request, headers, docId)
    const chunkText = chunks.map((chunk) => String(chunk.content || '')).join('\n')

    noRawPii(chunkText, {
      phone: PII_FIXTURES.phone,
      idCard: PII_FIXTURES.idCard,
      email: PII_FIXTURES.email,
      bank: PII_FIXTURES.bank,
    })

    expect(chunkText).toMatch(/138\*{4}5678/)
    expect(chunkText).toMatch(/139\*{4}4321/)
    expect(chunkText).toMatch(/440103\*{8}1234/)
    expect(chunkText).toMatch(/\d{6}\*{8}\d{4}/)
    expect(chunkText).toMatch(/a\*+@/)

    const report = parseCleanReport(detail)
    expect(sumPiiHits(report)).toBeGreaterThanOrEqual(4)

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '04')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(kbId)
  }
})
