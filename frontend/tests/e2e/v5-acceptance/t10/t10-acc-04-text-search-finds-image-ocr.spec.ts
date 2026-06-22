import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  login,
  loginPage,
  openDebugConsole,
  searchByText,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T10 ACC-04 @e2e-dashscope-real hybrid text search recalls IMAGE OCR chunk', async ({ page, request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-04', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.pureImageWithText))
    await waitForStatus(request, headers, docId, 'COMPLETED')

    const results = await searchByText(request, headers, kbId, '今日营收', 'hybrid', 8)
    const imageHit = results.find((r) => r.docId === docId && r.chunkModality === 'IMAGE')
    expect(imageHit, 'hybrid search should return IMAGE chunk').toBeTruthy()
    expect(imageHit?.finalScore || imageHit?.vectorScore || 0).toBeGreaterThan(0.5)

    await loginPage(page)
    await openDebugConsole(page)
    await page.locator('.param-select').first().selectOption(String(kbId))
    await page.locator('.param-select').nth(2).selectOption('hybrid')
    await page.locator('.search-input').fill('今日营收')
    await page.getByRole('button', { name: '🔍 检索' }).click()
    await expect(page.locator('.result-modality').filter({ hasText: 'IMAGE' }).first()).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.result-text').filter({ hasText: /今日营收|12345/ }).first()).toBeVisible()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
