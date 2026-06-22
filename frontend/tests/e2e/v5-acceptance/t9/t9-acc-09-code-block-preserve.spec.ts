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

test.describe.configure({ timeout: 360_000 })

test('T9 ACC-09 markdown and recursive chunkers preserve fenced code blocks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-09-code', {
    defaultStrategy: 'MARKDOWN_HEADING',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 500, overlap: 50, maxHeadingLevel: 3 },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.codeBlocks)
    const chunks = getDbChunks(docId)

    expectStrategies(chunks, 'MARKDOWN_HEADING')
    for (const chunk of chunks) {
      const fenceCount = (chunk.content.match(/```/g) || []).length
      expect(fenceCount % 2).toBe(0)
    }

    const mainChunks = chunks.filter((chunk) => chunk.content.includes('def main():'))
    expect(mainChunks).toHaveLength(1)
    expect(mainChunks[0].content).toContain('total += 28')
    expect(mainChunks[0].content).toContain('return total')
    expect(mainChunks[0].chunkerParamsJson.strategy).toBe('MARKDOWN_HEADING')
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
