import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  getChunks,
  getDocument,
  login,
  loginPage,
  parseCleanReport,
  uploadFile,
  waitForStatus,
  cleanupKb,
  screenshotPair,
  deleteCleanProfile,
} from '../_helpers/t8-common'
import { T8_FIXTURES, fixtureText } from './fixtures/t8-fixtures'
import { expectUnicodeNormalized, expectL1Normalized } from '../_helpers/t8-asserts'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-01 L1 normalize whitespace', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-01-l1')
  try {
    await createCleanProfile(kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'MASK',
    })
    const file = T8_FIXTURES.unicode
    const fixture = fixtureText(file)
    const docId = await uploadFile(request, headers, kbId, asset(file))

    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)
    const detail = await getDocument(request, headers, docId)
    // chunk_metadata_json is null for TEXT chunks; the real content lives in the `content` column,
    // which is what /documents/{id}/chunks exposes.
    const chunks = await getChunks(request, headers, docId)
    const chunksText = chunks
      .filter((chunk) => String(chunk.chunkModality || chunk.modality || 'TEXT').toUpperCase() === 'TEXT')
      .map((chunk) => String(chunk.content || ''))
      .join('\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim()

    expectUnicodeNormalized(chunksText, fixture)

    const report = parseCleanReport(detail)
    expectL1Normalized(report)
    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '01')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(kbId)
  }
})
