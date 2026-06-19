import { expect, test } from '@playwright/test'

async function mockDashboard(page) {
  await page.route('**/api/v1/metrics/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          kbCount: 0,
          documentCount: 0,
          chunkCount: 0,
          todayApiCalls: 0,
          avgLatencyMs: 0,
          hitRate: 0,
          recentActivities: [],
        },
      }),
    })
  })
}

test.describe('RAGForge auth layout', () => {
  test('login route renders outside sidebar layout', async ({ page }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })

    await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible()
    await expect(page.locator('.sidebar')).toHaveCount(0)
    await expect(page.getByRole('link', { name: /知识库管理|检索调试台|API 网关/ })).toHaveCount(0)
  })

  test('password login stores access token in memory only', async ({ page }) => {
    await mockDashboard(page)
    await page.route('**/api/auth/login', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ account: 'admin', password: 'Admin123!', remember: true })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'set-cookie': 'rf_csrf=test-csrf; Path=/' },
        body: JSON.stringify({
          code: 200,
          data: {
            access_token: 'test-access-token',
            user: { displayName: 'guandezhi@ragforge.cn', ragRole: 'ADMIN', tenantSlug: 'personal-26' },
          },
        }),
      })
    })

    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await expect(page.getByText(/注册/)).toHaveCount(0)
    await page.getByLabel('账号 / 手机号 / 邮箱').fill('admin')
    await page.getByLabel('密码').fill('Admin123!')
    await page.getByRole('button', { name: '登 录' }).click()

    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByText('驾驶舱').first()).toBeVisible()
    await expect.poll(() => page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
    await expect.poll(() => page.evaluate(() => sessionStorage.getItem('access_token'))).toBeNull()
  })

  test('mobile login sends sms and signs in', async ({ page }) => {
    await mockDashboard(page)
    await page.route('**/api/auth/sms/send', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ phone: '13800000000', scene: 'login' })
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: { sent: true } }) })
    })
    await page.route('**/api/auth/login-mobile', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ phone: '13800000000', code: '123456' })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: { accessToken: 'mobile-access-token', user: { displayName: 'mobile-admin' } } }),
      })
    })

    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByRole('tab', { name: '手机验证码' }).click()
    await page.getByLabel('手机号').fill('13800000000')
    await page.getByRole('button', { name: '获取验证码' }).click()
    await expect(page.getByRole('button', { name: /后重发/ })).toBeDisabled()
    await page.getByLabel('验证码').fill('123456')
    await page.getByRole('button', { name: '登 录' }).click()

    await expect(page).toHaveURL(/\/$/)
  })

  test('login error shows remaining attempts and captcha', async ({ page }) => {
    await page.route('**/api/auth/login', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 401,
          data: {
            message: '账号或密码错误',
            remainingAttempts: 2,
            captchaRequired: true,
            challengeId: 'captcha-1',
          },
        }),
      })
    })

    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByLabel('账号 / 手机号 / 邮箱').fill('admin')
    await page.getByLabel('密码').fill('bad-password')
    await page.getByRole('button', { name: '登 录' }).click()

    await expect(page.getByText('账号或密码错误，剩余 2 次')).toBeVisible()
    await expect(page.getByLabel('图形验证码')).toBeVisible()
    await expect(page.getByRole('button', { name: /后重试/ })).toBeDisabled()
  })
})
