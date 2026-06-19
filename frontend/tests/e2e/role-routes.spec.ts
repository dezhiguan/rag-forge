import { expect, test } from '@playwright/test'

async function mockDashboard(page) {
  await page.route('**/api/v1/metrics/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: { recentActivities: [] } }),
    })
  })
}

async function loginAs(page, user) {
  await mockDashboard(page)
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: { access_token: `${user.ragRole}-token`, user } }),
    })
  })
  await page.route('**/api/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: { access_token: `${user.ragRole}-refresh-token`, user } }),
    })
  })
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByLabel('账号 / 手机号 / 邮箱').fill(user.displayName)
  await page.getByLabel('密码').fill('Admin123!')
  await page.getByRole('button', { name: '登 录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

test.describe('RAGForge route role matrix', () => {
  test('KB_VIEWER sees only read/debug menu and direct admin route goes 403', async ({ page }) => {
    await loginAs(page, {
      displayName: 'viewer@ragforge.cn',
      ragRole: 'KB_VIEWER',
      scopes: ['rag:dashboard:read', 'rag:kb:read', 'rag:doc:read', 'rag:debug:run', 'rag:audit:read'],
    })

    await expect(page.getByText('知识库管理').first()).toBeVisible()
    await expect(page.getByText('检索调试台').first()).toBeVisible()
    await expect(page.getByText('评测实验室')).toHaveCount(0)
    await expect(page.getByText('API 网关')).toHaveCount(0)
    await expect(page.getByText(/系统配置|用户管理/)).toHaveCount(0)

    await page.goto('/api', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/403$/)
    await expect(page.getByRole('heading', { name: '无权访问' })).toBeVisible()
  })

  test('KB_EDITOR can open eval but cannot open api gateway', async ({ page }) => {
    await loginAs(page, {
      displayName: 'editor@ragforge.cn',
      ragRole: 'KB_EDITOR',
      scopes: ['rag:dashboard:read', 'rag:kb:read', 'rag:doc:read', 'rag:debug:run', 'rag:eval:write'],
    })

    await expect(page.getByText('评测实验室').first()).toBeVisible()
    await expect(page.getByText('API 网关')).toHaveCount(0)

    await page.goto('/eval', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/eval$/)
    await page.goto('/api', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/403$/)
  })

  test('ADMIN can open api gateway', async ({ page }) => {
    await loginAs(page, {
      displayName: 'admin@ragforge.cn',
      ragRole: 'ADMIN',
      scopes: ['rag:dashboard:read', 'rag:apikey:admin'],
    })

    await expect(page.getByText('API 网关').first()).toBeVisible()
    await page.goto('/api', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/api$/)
  })
})
