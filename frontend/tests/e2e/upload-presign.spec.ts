import { expect, test } from '@playwright/test'

test('uploads a markdown document through presign OSS flow', async ({ page }) => {
  const completedRequests: string[] = []

  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          accessToken: 'test-access-token',
          user: { displayName: 'upload-admin', ragRole: 'ADMIN' },
        },
      }),
    })
  })
  await page.route('**/api/v1/metrics/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: {} }),
    })
  })
  await page.route('**/api/v1/kb', async (route) => {
    completedRequests.push('GET /kb')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: [{ id: 1, name: '默认知识库', status: 'active', docCount: 0, chunkCount: 0 }],
      }),
    })
  })
  await page.route('**/api/v1/kb/1/documents**', async (route) => {
    completedRequests.push('GET /kb/1/documents')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          list: [
            {
              id: 101,
              kbId: 1,
              filename: 'fixture.md',
              fileSize: 14,
              parseStatus: 'COMPLETED',
              chunkCount: 1,
              version: 1,
              createdAt: '2026-06-24T00:00:00',
            },
          ],
        },
      }),
    })
  })
  await page.route('**/api/v1/uploads/presign', async (route) => {
    const body = route.request().postDataJSON()
    expect(body).toMatchObject({
      kbId: 1,
      filename: 'fixture.md',
      contentType: 'text/markdown',
      declaredSize: 14,
    })
    completedRequests.push('POST /uploads/presign')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        uploadToken: 'uplt_test',
        presignedPutUrl: 'https://rag-raw-docs.oss-cn-guangzhou.aliyuncs.com/tenant/kb_1/uplt_test/fixture.md',
        storageBucket: 'rag-raw-docs',
        storageKey: 'tenant/kb_1/uplt_test/fixture.md',
        expiresAt: '2026-06-24T00:30:00Z',
      }),
    })
  })
  await page.route('https://rag-raw-docs.oss-cn-guangzhou.aliyuncs.com/**', async (route) => {
    expect(route.request().method()).toBe('PUT')
    expect(route.request().headers()['content-type']).toBe('text/markdown')
    completedRequests.push('PUT OSS')
    await route.fulfill({
      status: 200,
      headers: { ETag: 'etag-fixture', 'x-oss-request-id': 'oss-request-1' },
      body: '',
    })
  })
  await page.route('**/api/v1/documents/register', async (route) => {
    const body = route.request().postDataJSON()
    expect(body.kbId).toBe(1)
    expect(body.uploadToken).toBe('uplt_test')
    expect(body.identity.contentMd5).toMatch(/^[a-f0-9]{64}$/)
    completedRequests.push('POST /documents/register')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ documentId: 101, status: 'CREATED' }),
    })
  })
  await page.route('**/api/v1/documents/101/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: { parseStatus: 'COMPLETED', chunkCount: 1 } }),
    })
  })

  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByLabel('账号 / 手机号 / 邮箱').fill('admin')
  await page.getByLabel('密码').fill('Admin123!')
  await page.getByRole('button', { name: '登 录' }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.getByRole('link', { name: /知识库管理/ }).click()
  await expect(page).toHaveURL(/\/knowledge/)

  const fileInput = page.locator('input[type="file"]').first()
  await fileInput.setInputFiles({
    name: 'fixture.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# Fixture doc\n'),
  })

  await expect(page.getByText('fixture.md').first()).toBeVisible()
  await expect(page.getByText('完成').first()).toBeVisible()
  await expect.poll(() => completedRequests).toContain('POST /uploads/presign')
  await expect.poll(() => completedRequests).toContain('PUT OSS')
  await expect.poll(() => completedRequests).toContain('POST /documents/register')
  await expect.poll(() => completedRequests).toContain('GET /kb/1/documents')
})
