import { expect, test } from '@playwright/test'

test.describe('RAGForge auth layout', () => {
  test('login route renders outside sidebar layout', async ({ page }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })

    await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible()
    await expect(page.locator('.sidebar')).toHaveCount(0)
    await expect(page.getByRole('link', { name: /知识库管理|检索调试台|API 网关/ })).toHaveCount(0)
  })
})
