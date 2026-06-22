import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectOverlap,
  expectStrategies,
  getDbChunks,
  login,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 360_000 })

test('T9 ACC-05 RECURSIVE splits an extra long paragraph within chunkSize', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-05-recursive', {
    defaultStrategy: 'RECURSIVE',
    fallbackChain: ['FIXED_WINDOW'],
    params: { chunkSize: 512, overlap: 50 },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.extraLongParagraph)
    const chunks = getDbChunks(docId)

    expectStrategies(chunks, 'RECURSIVE')
    expect(chunks.length).toBeGreaterThan(5)
    for (const chunk of chunks) {
      expect(chunk.tokenCount).toBeLessThanOrEqual(Math.ceil(512 * 1.1))
    }
    for (let i = 1; i < Math.min(chunks.length, 5); i++) {
      expectOverlap(chunks[i - 1].content, chunks[i].content, 50)
    }
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
