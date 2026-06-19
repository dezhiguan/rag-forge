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

async function login(page) {
  await mockDashboard(page)
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          access_token: 'logout-test-token',
          user: { displayName: 'guandezhi@ragforge.cn', ragRole: 'ADMIN', tenantSlug: 'personal-26' },
        },
      }),
    })
  })

  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByLabel('账号 / 手机号 / 邮箱').fill('admin')
  await page.getByLabel('密码').fill('Admin123!')
  await page.getByRole('button', { name: '登 录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

test.describe('RAGForge logout menu', () => {
  test('shows user menu and logs out current device with confirmation', async ({ page }) => {
    await login(page)

    let logoutCalled = false
    await page.route('**/api/auth/logout', async (route) => {
      logoutCalled = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200 }) })
    })
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('确认退出')
      await dialog.accept()
    })

    await page.getByRole('button', { name: /G/ }).click()
    await expect(page.getByRole('menuitem', { name: '个人设置' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '安全中心' })).toBeVisible()
    await page.getByRole('menuitem', { name: '退出登录' }).click()

    await expect(page).toHaveURL(/\/login$/)
    expect(logoutCalled).toBeTruthy()
  })

  test('logout all requires password and mentions CareerMate sessions', async ({ page }) => {
    await login(page)

    await page.route('**/api/auth/logout-all', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ password: 'Admin123!' })
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200 }) })
    })

    await page.getByRole('button', { name: /G/ }).click()
    await page.getByRole('menuitem', { name: '退出所有设备' }).click()
    await expect(page.getByRole('dialog', { name: '退出所有设备' })).toBeVisible()
    await expect(page.getByText('CareerMate 全端 session')).toBeVisible()
    await expect(page.getByRole('button', { name: '确认退出所有设备' })).toBeDisabled()
    await page.getByLabel('当前密码').fill('Admin123!')
    await page.getByRole('button', { name: '确认退出所有设备' }).click()

    await expect(page).toHaveURL(/\/login$/)
  })
})
