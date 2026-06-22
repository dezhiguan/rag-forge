import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectStrategies,
  getDbChunks,
  login,
  test,
  type ChunkerStrategyName,
  type TimingRecord,
  t9Asset,
  T9_FIXTURES,
  uploadFile,
  waitForStatus,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 600_000 })

test('T9 ACC-10 five chunker strategies complete within the headed perf baseline', async ({ request }) => {
  const headers = await login(request)
  const strategies: ChunkerStrategyName[] = [
    'FIXED_WINDOW',
    'MARKDOWN_HEADING',
    'RECURSIVE',
    'TABLE_AWARE',
    'SEMANTIC',
  ]
  const timings: TimingRecord[] = []
  const kbIds: number[] = []
  try {
    const jobs: Array<{ strategy: ChunkerStrategyName, kbId: number, docId: number, startedAt: number }> = []
    for (const strategy of strategies) {
      const kbId = await createKbWithChunkerProfile(request, headers, `t9-acc-10-${strategy.toLowerCase()}`, {
        defaultStrategy: strategy,
        fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
        params: { chunkSize: 500, overlap: 50, tablePolicy: 'WHOLE', simThreshold: 0.65 },
      })
      kbIds.push(kbId)

      const docId = await uploadFile(request, headers, kbId, t9Asset(T9_FIXTURES.markdownHeadings))
      jobs.push({ strategy, kbId, docId, startedAt: Date.now() })
    }

    const suiteStartedAt = Math.min(...jobs.map((job) => job.startedAt))
    await Promise.all(
      jobs.map(async (job) => {
        await waitForStatus(request, headers, job.docId, 'COMPLETED', 420_000)
        timings.push({ ...job, elapsedMs: Date.now() - job.startedAt })
      }),
    )

    for (const job of jobs) {
      const chunks = getDbChunks(job.docId)
      expectStrategies(chunks, job.strategy)
    }

    const totalMs = Date.now() - suiteStartedAt
    console.info('[T9 ACC-10 timings]', timings.map((item) => `${item.strategy}=${item.elapsedMs}ms`).join(', '))
    expect(totalMs).toBeLessThanOrEqual(60_000)

    const elapsed = Object.fromEntries(timings.map((item) => [item.strategy, item.elapsedMs]))
    expect(elapsed.FIXED_WINDOW).toBeLessThanOrEqual(elapsed.MARKDOWN_HEADING + 15_000)
    expect(elapsed.MARKDOWN_HEADING).toBeLessThanOrEqual(elapsed.RECURSIVE + 15_000)
    expect(elapsed.RECURSIVE).toBeLessThanOrEqual(elapsed.SEMANTIC + 15_000)
    expect(Math.max(...Object.values(elapsed))).toBeLessThanOrEqual(60_000)
  } finally {
    for (const kbId of kbIds.reverse()) {
      await cleanupKb(request, headers, kbId)
    }
  }
})
