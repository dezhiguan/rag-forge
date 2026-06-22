import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  getDbChunks,
  login,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 360_000 })

test('T9 ACC-08 short fragments are merged instead of exploding into many chunks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-08-fragments', {
    defaultStrategy: 'MARKDOWN_HEADING',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 500, overlap: 50 },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.shortFragments)
    const chunks = getDbChunks(docId)

    expect(chunks.length).toBeGreaterThan(1)
    expect(chunks.length).toBeLessThanOrEqual(20)
    const nonFinalChunks = chunks.slice(0, -1)
    expect(nonFinalChunks.every((chunk) => chunk.content.length >= 100)).toBeTruthy()
    expect(chunks.every((chunk) => ['RECURSIVE', 'FIXED_WINDOW'].includes(chunk.chunkerStrategy))).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
