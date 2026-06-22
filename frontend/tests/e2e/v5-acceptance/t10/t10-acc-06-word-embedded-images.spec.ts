import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  getChunkRaw,
  login,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { expectVlVectorDim, modalityCounts } from '../_helpers/t10-asserts'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 300_000 })

test('T10 ACC-06 @e2e-dashscope-real Word embedded images produce TEXT + IMAGE chunks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-06', 'ON')
  try {
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.wordEmbedded))
    await waitForStatus(request, headers, docId, 'COMPLETED', 300_000)

    const raw = await getChunkRaw(request, headers, docId)
    const counts = modalityCounts(raw)

    expect(counts.TEXT || 0).toBeGreaterThan(0)
    expect(counts.IMAGE || 0).toBeGreaterThanOrEqual(3)
    expectVlVectorDim(raw)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
