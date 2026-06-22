import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  getChunkRaw,
  login,
  reprocess,
  setDocumentStatus,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { expectVlVectorDim } from '../_helpers/t10-asserts'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T10 ACC-09 @e2e-dashscope-real reprocess rebuilds vl_vector space', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-09', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.pureImageWithText))
    await waitForStatus(request, headers, docId, 'COMPLETED')

    const before = await getChunkRaw(request, headers, docId)
    expectVlVectorDim(before)

    const started = Date.now()
    await setDocumentStatus(request, headers, docId, 'FAILED')
    await reprocess(request, headers, docId)
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const elapsedMs = Date.now() - started

    const after = await getChunkRaw(request, headers, docId)
    expectVlVectorDim(after)
    expect(after.map((c) => c.chunkId)).not.toEqual(before.map((c) => c.chunkId))
    expect(elapsedMs).toBeLessThan(30_000)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
