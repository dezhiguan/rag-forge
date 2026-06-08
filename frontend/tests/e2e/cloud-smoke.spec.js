import { expect, test } from '@playwright/test'

const apiBaseURL = process.env.PLAYWRIGHT_API_BASE_URL || 'http://ragforge.net/api/v1'
const serverErrorPattern = /(?:500 Internal Server Error|502 Bad Gateway|404 Not Found|Whitelabel Error Page)/i

async function expectPageReady(page, visibleTextPattern) {
  await expect(page.locator('body')).not.toContainText(serverErrorPattern)
  await expect(page.getByText(visibleTextPattern).first()).toBeVisible()
}

test.describe('RAGForge cloud smoke', () => {
  test('health endpoint is available', async ({ request }) => {
    const response = await request.get(`${apiBaseURL}/health`)

    expect(response.status()).toBe(200)
    expect((await response.text()).trim().length).toBeGreaterThan(0)
  })

  test('dashboard is available from browser entry', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })

    await expectPageReady(page, /驾驶舱/)
    await expect(page.getByText('知识库管理').first()).toBeVisible()
    await expect(page.getByText('检索调试台').first()).toBeVisible()
    await expect(page.getByText('性能诊断').first()).toBeVisible()
    await expect(page.getByText('评测实验室').first()).toBeVisible()
    await expect(page.getByText('API 网关').first()).toBeVisible()
  })

  test('core routes are reachable without mutating data', async ({ page }) => {
    const routes = [
      { path: '/knowledge', text: /知识库管理|创建知识库/ },
      { path: '/debug', text: /检索调试台|检索参数/ },
      { path: '/perf-probe', text: /性能诊断/ },
      { path: '/eval', text: /评测实验室/ },
      { path: '/api-gateway', text: /API 网关|API Keys/ },
    ]

    for (const route of routes) {
      await page.goto(route.path, { waitUntil: 'domcontentloaded' })
      await expectPageReady(page, route.text)
    }
  })
})
