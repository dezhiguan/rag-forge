import {
  cleanupKb,
  createEvalDataset,
  createEvalQuestions,
  createKbWithChunkerProfile,
  expect,
  login,
  loginPage,
  runChunkerAb,
  screenshotT9,
  test,
  T9_FIXTURES,
  uploadAndWait,
} from '../_helpers/t9-common'

test.describe.configure({ timeout: 480_000 })

test('T9 ACC-07 EvaluationLab chunker A/B renders four strategy results', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKbWithChunkerProfile(request, headers, 't9-acc-07-ab-ui', {
    defaultStrategy: 'TABLE_AWARE',
    fallbackChain: ['RECURSIVE', 'FIXED_WINDOW'],
    params: { chunkSize: 500, overlap: 50, tablePolicy: 'WHOLE' },
  })
  try {
    await uploadAndWait(request, headers, kbId, T9_FIXTURES.mixedTable)
    const datasetId = await createEvalDataset(request, headers, kbId, 't9-acc-07-ab')
    await createEvalQuestions(request, headers, datasetId, [
      {
        question: '表格里 tableRows 指标是多少？',
        expectedTextSnippets: ['| tableRows | 5 | 2 | 5 |'],
      },
    ])

    const apiProbe = await runChunkerAb(
      request,
      headers,
      datasetId,
      ['MARKDOWN_HEADING', 'FIXED_WINDOW', 'SEMANTIC', 'TABLE_AWARE'],
      { chunkSize: 500, overlap: 50 },
    )
    expect(apiProbe.results).toHaveLength(4)

    await loginPage(page)
    await page.goto(`/eval?tab=chunkerAb`, { waitUntil: 'domcontentloaded' })
    await page.locator('select').first().selectOption(String(datasetId))

    for (const strategy of ['RECURSIVE', 'MARKDOWN_HEADING', 'FIXED_WINDOW', 'SEMANTIC', 'TABLE_AWARE']) {
      await page.locator(`input[value="${strategy}"]`).setChecked(false)
    }
    for (const strategy of ['MARKDOWN_HEADING', 'FIXED_WINDOW', 'SEMANTIC', 'TABLE_AWARE']) {
      await page.locator(`input[value="${strategy}"]`).setChecked(true)
    }

    const abResponse = page.waitForResponse((res) =>
      res.url().includes('/api/v1/evaluation/chunker-ab') && res.status() === 200,
    )
    await page.getByRole('button', { name: /运行分块 A\/B/ }).click()
    await abResponse

    const rows = page.locator('.table-card .data-table tbody tr')
    await expect(rows).toHaveCount(4)
    const resultTable = page.getByRole('table')
    await expect(resultTable.getByText('Markdown Heading')).toBeVisible()
    await expect(resultTable.getByText('Fixed Window')).toBeVisible()
    await expect(resultTable.getByText('Semantic')).toBeVisible()
    await expect(resultTable.getByText('Table Aware')).toBeVisible()
    await expect(page.getByText(/unsupported|不支持|失败/i)).toHaveCount(0)

    await screenshotT9(page, testInfo, 'acc-07-ab-ui-four-columns')
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
