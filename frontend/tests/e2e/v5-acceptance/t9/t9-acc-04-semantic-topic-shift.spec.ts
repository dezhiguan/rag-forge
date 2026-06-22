import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectStrategies,
  getDbChunks,
  login,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 480_000 })

test('T9 ACC-04 SEMANTIC keeps obvious topic shifts separated', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-04-semantic', {
    defaultStrategy: 'SEMANTIC',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 900, overlap: 50, simThreshold: 0.65 },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.semanticTopicShift, 420_000)
    const chunks = getDbChunks(docId)

    expectStrategies(chunks, 'SEMANTIC')
    expect(chunks.length).toBeGreaterThanOrEqual(2)
    expect(chunks.length).toBeLessThanOrEqual(8)
    expect(
      chunks.some((chunk) =>
        chunk.content.includes('人工智能')
        && chunk.content.includes('心脏病')
        && chunk.content.includes('教育部'),
      ),
    ).toBeFalsy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
