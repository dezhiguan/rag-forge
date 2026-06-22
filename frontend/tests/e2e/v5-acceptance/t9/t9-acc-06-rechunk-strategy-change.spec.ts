import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectStrategies,
  getDbChunks,
  getDocumentChunkCount,
  login,
  rechunkDocument,
  setKbChunkerProfile,
  test,
  T9_FIXTURES,
  uniqueChunkIds,
  uploadAndWait,
  waitForStatus,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 420_000 })

test('T9 ACC-06 rechunk removes old chunks and applies the updated KB strategy', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-06-rechunk', {
    defaultStrategy: 'MARKDOWN_HEADING',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 500, overlap: 50 },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.markdownHeadings)
    const oldChunks = getDbChunks(docId)
    const oldIds = new Set(uniqueChunkIds(oldChunks))
    const oldCount = oldChunks.length

    expectStrategies(oldChunks, 'MARKDOWN_HEADING')
    await setKbChunkerProfile(kbId, {
      defaultStrategy: 'FIXED_WINDOW',
      fallbackChain: ['RECURSIVE'],
      params: { chunkSize: 256, overlap: 24 },
    })
    await rechunkDocument(request, headers, docId)
    await waitForStatus(request, headers, docId, 'COMPLETED', 360_000)

    const newChunks = getDbChunks(docId)
    expectStrategies(newChunks, 'FIXED_WINDOW')
    expect(newChunks.length).toBeGreaterThan(oldCount)
    expect(newChunks.some((chunk) => oldIds.has(chunk.chunkId))).toBeFalsy()
    expect(getDocumentChunkCount(docId)).toBe(newChunks.length)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
