import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getStatus, login, loginPage, openDocumentDetail, uploadFile } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-10 @e2e-dashscope-real corrupt image ends FAILED and shows reprocess button', async ({ page, request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-corrupt-image', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('corrupt.png'))
    await expect
      .poll(async () => (await getStatus(request, headers, docId)).parseStatus, {
        timeout: 180_000,
        intervals: [2_000, 5_000],
      })
      .toBe('FAILED')
    const status = await getStatus(request, headers, docId)
    expect(status.errorMsg || '').toMatch(/OCR|decode|format|image|图片/i)
    await loginPage(page)
    await openDocumentDetail(page, docId)
    await expect(page.getByRole('button', { name: '重新处理' })).toBeVisible()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
