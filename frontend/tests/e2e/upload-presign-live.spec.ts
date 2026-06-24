import { expect, test } from '@playwright/test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const FIXTURE_TEMPLATE = path.join(__dirname, 'fixtures', 'upload-presign-fixture.md')

test.describe('OSS presign live upload', () => {
  test('uploads via real presign → OSS PUT → register', async ({ page }) => {
    test.setTimeout(180_000)

    const runId = Date.now()
    const fixtureName = `upload-presign-live-${runId}.md`
    const fixturePath = path.join(os.tmpdir(), fixtureName)
    fs.writeFileSync(
      fixturePath,
      `${fs.readFileSync(FIXTURE_TEMPLATE, 'utf8')}\n\n<!-- run ${runId} -->\n`,
      'utf8',
    )

    const networkLog: string[] = []
    page.on('request', (req) => {
      const url = req.url()
      if (url.includes('/api/v1/uploads/presign')) networkLog.push('POST /uploads/presign')
      if (url.includes('/api/v1/documents/register')) networkLog.push('POST /documents/register')
      if (url.includes('.aliyuncs.com/') && req.method() === 'PUT') networkLog.push('PUT OSS')
      if (/\/api\/v1\/kb\/\d+\/documents/.test(url) && req.method() === 'GET') {
        networkLog.push('GET /kb/documents')
      }
    })

    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByLabel('账号 / 手机号 / 邮箱').fill('admin')
    await page.getByLabel('密码').fill('admin')
    await page.getByRole('button', { name: '登 录' }).click()
    await expect(page).toHaveURL(/\/$/, { timeout: 30_000 })

    await page.getByRole('link', { name: /知识库管理/ }).click()
    await expect(page).toHaveURL(/\/knowledge/)

    const presignKbOption = page.locator('select.select option', { hasText: /t5-presign-real/ }).first()
    await expect(presignKbOption).toBeAttached({ timeout: 10_000 })
    const kbValue = await presignKbOption.getAttribute('value')
    await page.locator('select.select').selectOption(kbValue || '')

    const presignPromise = page.waitForResponse(
      (res) => res.url().includes('/api/v1/uploads/presign') && res.request().method() === 'POST',
      { timeout: 60_000 },
    )
    const ossPutPromise = page.waitForRequest(
      (req) => req.method() === 'PUT' && req.url().includes('.aliyuncs.com/'),
      { timeout: 120_000 },
    )
    const registerPromise = page.waitForResponse(
      (res) => res.url().includes('/api/v1/documents/register') && res.request().method() === 'POST',
      { timeout: 120_000 },
    )

    const fileInput = page.locator('input[type="file"]').first()
    await fileInput.setInputFiles(fixturePath)

    const presignRes = await presignPromise
    expect(presignRes.status()).toBe(200)
    const presignBody = await presignRes.json()
    expect(presignBody.uploadToken || presignBody.data?.uploadToken).toBeTruthy()
    expect(presignBody.presignedPutUrl || presignBody.data?.presignedPutUrl).toMatch(/aliyuncs\.com/)

    const ossReq = await ossPutPromise
    expect(ossReq.headers()['content-type']).toMatch(/markdown|octet-stream/)

    const registerRes = await registerPromise
    expect(registerRes.status()).toBe(200)

    await expect(page.getByText(fixtureName).first()).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText('完成').first()).toBeVisible({ timeout: 30_000 })

    await expect.poll(() => networkLog.filter((x) => x === 'POST /uploads/presign').length).toBeGreaterThan(0)
    await expect.poll(() => networkLog.filter((x) => x === 'PUT OSS').length).toBeGreaterThan(0)
    await expect.poll(() => networkLog.filter((x) => x === 'POST /documents/register').length).toBeGreaterThan(0)
  })
})
