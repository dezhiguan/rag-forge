import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  getChunkRaw,
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
    const raw = await getChunkRaw(request, headers, docId)

    const chunksText = raw
      .filter((chunk) => (chunk.modality || 'TEXT').toUpperCase() === 'TEXT')
      .map((chunk) => {
        if (!chunk.chunkMetadataJson) return ''
        try {
          const parsed = JSON.parse(chunk.chunkMetadataJson)
          return String(parsed.content || '')
        } catch {
          return ''
        }
      })
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
