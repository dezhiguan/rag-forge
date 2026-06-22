import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectStrategies,
  getDbChunks,
  login,
  tableLineCount,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 360_000 })

test('T9 ACC-03 TABLE_AWARE preserves a markdown table as one chunk', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-03-table', {
    defaultStrategy: 'TABLE_AWARE',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 500, overlap: 50, tablePolicy: 'WHOLE' },
  })
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.mixedTable)
    const chunks = getDbChunks(docId)

    expectStrategies(chunks, 'TABLE_AWARE')
    const tableChunks = chunks.filter((chunk) => tableLineCount(chunk.content) >= 6)
    expect(tableChunks).toHaveLength(1)

    const tableChunk = tableChunks[0]
    expect(tableChunk.chunkerParamsJson.tablePolicy).toBe('WHOLE')
    expect(tableChunk.content).toContain('| 指标 | MARKDOWN_HEADING | FIXED_WINDOW | TABLE_AWARE |')
    expect(tableChunk.content).toContain('| tableRows | 5 | 2 | 5 |')
    expect(tableLineCount(tableChunk.content)).toBe(6)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
