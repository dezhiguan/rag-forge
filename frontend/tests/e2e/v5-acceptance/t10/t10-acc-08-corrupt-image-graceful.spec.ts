import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  getChunkRaw,
  getDocument,
  login,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { modalityCounts } from '../_helpers/t10-asserts'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T10 ACC-08 @e2e-dashscope-real corrupt embedded image fails gracefully in mixed PDF', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-08', 'ON')
  try {
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.pdfCorruptMixed))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)

    const doc = await getDocument(request, headers, docId)
    const raw = await getChunkRaw(request, headers, docId)
    const counts = modalityCounts(raw)

    expect(doc.parseStatus).toBe('COMPLETED')
    expect((counts.TEXT || 0) + (counts.IMAGE || 0)).toBeGreaterThan(0)
    expect(counts.IMAGE || 0).toBeGreaterThanOrEqual(1)

    const warnings = doc.warnings || doc.warningList || doc.parseWarnings
    if (warnings) {
      expect(JSON.stringify(warnings)).toMatch(/IMAGE_OCR_FAILED|OCR|corrupt|损坏/i)
    }
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
