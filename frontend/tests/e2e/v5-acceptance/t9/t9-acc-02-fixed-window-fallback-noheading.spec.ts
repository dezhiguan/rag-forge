import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectNoStrategy,
  getDbChunks,
  login,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 360_000 })

test('T9 ACC-02 no-heading plain text falls back from MARKDOWN_HEADING', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-02-fallback', {
    defaultStrategy: 'MARKDOWN_HEADING',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 500, overlap: 50 },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.noHeadingsPlain)
    const chunks = getDbChunks(docId)

    expect(chunks.length).toBeGreaterThan(1)
    expectNoStrategy(chunks, 'MARKDOWN_HEADING')
    expect(chunks.some((chunk) => ['RECURSIVE', 'FIXED_WINDOW'].includes(chunk.chunkerStrategy))).toBeTruthy()

    const avgLength = chunks.reduce((sum, chunk) => sum + chunk.content.length, 0) / chunks.length
    expect(avgLength).toBeGreaterThan(300)
    expect(avgLength).toBeLessThan(560)
    expect(chunks.every((chunk) => Number(chunk.chunkerParamsJson.chunkSize) === 500)).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
