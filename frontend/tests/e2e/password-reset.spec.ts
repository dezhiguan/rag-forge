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

test.describe('RAGForge password reset', () => {
  test('completes three-step reset without exposing reset ticket', async ({ page }) => {
    await mockDashboard(page)

    await page.route('**/api/auth/password/reset/init', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ account: 'admin', phone: '13800000000' })
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: { sent: true } }) })
    })

    await page.route('**/api/auth/password/reset/verify', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ account: 'admin', phone: '13800000000', code: '123456' })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: { reset_ticket: 'reset-ticket-secret' } }),
      })
    })

    await page.route('**/api/auth/password/reset/confirm', async (route) => {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({
        account: 'admin',
        reset_ticket: 'reset-ticket-secret',
        new_password: 'NewPass123!',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: { access_token: 'reset-access-token', user: { displayName: 'admin' } } }),
      })
    })

    await page.goto('/auth/reset', { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { name: '验证身份' })).toBeVisible()
    await page.getByLabel('账号 / 邮箱').fill('admin')
    await page.getByLabel('手机号').fill('13800000000')
    await page.getByRole('button', { name: '发送验证码' }).click()

    await expect(page.getByText('如果账号信息匹配，验证码将发送到绑定手机号。')).toBeVisible()
    await expect(page.getByRole('heading', { name: '设置新密码' })).toBeVisible()
    await page.getByLabel('验证码').fill('123456')
    await page.getByLabel('新密码', { exact: true }).fill('NewPass123!')
    await page.getByLabel('确认新密码', { exact: true }).fill('NewPass123!')
    await page.getByRole('button', { name: '确认重置并登录' }).click()

    await expect(page.getByRole('heading', { name: '密码已更新' })).toBeVisible()
    await expect(page).toHaveURL(/\/$/)
    await expect.poll(() => page.evaluate(() => location.href.includes('reset-ticket-secret'))).toBeFalsy()
    await expect.poll(() => page.evaluate(() => localStorage.getItem('reset_ticket'))).toBeNull()
    await expect.poll(() => page.evaluate(() => sessionStorage.getItem('reset_ticket'))).toBeNull()
  })

  test('init uses enumeration-safe success copy on backend error', async ({ page }) => {
    await page.route('**/api/auth/password/reset/init', async (route) => {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 404, msg: 'not found' }) })
    })

    await page.goto('/auth/reset', { waitUntil: 'domcontentloaded' })
    await page.getByLabel('账号 / 邮箱').fill('missing')
    await page.getByLabel('手机号').fill('13900000000')
    await page.getByRole('button', { name: '发送验证码' }).click()

    await expect(page.getByText('如果账号信息匹配，验证码将发送到绑定手机号。')).toBeVisible()
    await expect(page.getByRole('heading', { name: '设置新密码' })).toBeVisible()
  })
})
