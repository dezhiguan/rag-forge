import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  getChunkRaw,
  getChunks,
  getDocument,
  login,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { expectVlVectorDim } from '../_helpers/t10-asserts'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T10 ACC-03 @e2e-dashscope-real no-text image completes with IMAGE chunk placeholder', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-03', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.pureImageNoText))
    await waitForStatus(request, headers, docId, 'COMPLETED')

    const doc = await getDocument(request, headers, docId)
    const chunks = await getChunks(request, headers, docId)
    const raw = await getChunkRaw(request, headers, docId)

    expect(doc.parseStatus).toBe('COMPLETED')
    expect(doc.chunkCount).toBe(1)
    expect(chunks).toHaveLength(1)
    expect(chunks[0].chunkModality).toBe('IMAGE')
    expect(chunks[0].content || '').toMatch(/\[图片：|mm-pure-image-no-text\.png\]/)
    expectVlVectorDim(raw)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
