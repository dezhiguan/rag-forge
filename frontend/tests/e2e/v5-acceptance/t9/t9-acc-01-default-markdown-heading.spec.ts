import {
  cleanupKb,
  createKbWithChunkerProfile,
  expect,
  expectStrategies,
  getDbChunks,
  login,
  loginPage,
  screenshotT9,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 360_000 })

test('T9 ACC-01 default MARKDOWN_HEADING preserves heading paths', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-01-heading')
  try {
    const docId = await uploadAndWait(request, headers, kbId, T9_FIXTURES.markdownHeadings)
    const chunks = getDbChunks(docId)

    expectStrategies(chunks, 'MARKDOWN_HEADING')
    expect(chunks.length).toBeGreaterThanOrEqual(5)
    expect(chunks.length).toBeLessThanOrEqual(7)
    expect(chunks.every((chunk) => !!chunk.headingPath)).toBeTruthy()
    expect(chunks.map((chunk) => chunk.headingPath)).toEqual([
      'Intro',
      'Intro/Setup',
      'Intro/Setup/Install',
      'Intro/Usage',
      'Intro/Usage/Debug Console',
      'Reference',
    ])

    const setupChunk = chunks.find((chunk) => chunk.headingPath === 'Intro/Setup')
    expect(setupChunk?.content).toContain('Setup 内容')
    expect(setupChunk?.content).not.toContain('Usage 内容')

    await loginPage(page)
    await page.goto(`/document/${docId}`, { waitUntil: 'domcontentloaded' })
    await screenshotT9(page, testInfo, 'acc-01-heading')
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
