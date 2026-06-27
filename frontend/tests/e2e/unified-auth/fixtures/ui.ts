import { expect, type Page } from '@playwright/test'
import { DEV_SMS_CODE, RAG_WEB } from './env'
import { gatewayLoginMobile, gatewayLoginPassword } from './gateway'
import { ragLoginMobile } from './api'

async function fulfillLoginRoutes(page: Page, token: string) {
  const body = JSON.stringify({
    code: 200,
    data: { access_token: token, accessToken: token, user: {} },
  })
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body })
  })
  await page.route('**/api/auth/login-mobile', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body })
  })
}

async function submitMobileLogin(page: Page, phone: string, code: string) {
  await page.goto(`${RAG_WEB}/login`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('tab', { name: '手机验证码' }).click()
  await page.getByLabel('手机号').fill(phone)
  await page.getByRole('button', { name: '获取验证码' }).click()
  await page.getByLabel('验证码').fill(code)
  await page.getByRole('button', { name: '登 录' }).click()
}

/** 优先走 RAG 真实认证代理；失败时回退网关换票 + route 注入 */
export async function loginViaPasswordUI(page: Page, account: string, password: string) {
  await page.goto(`${RAG_WEB}/login`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('tab', { name: '账号密码' }).click()
  await page.getByLabel('账号 / 手机号 / 邮箱').fill(account)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登 录' }).click()
  try {
    await expect(page).toHaveURL(/\/(\?.*)?$/, { timeout: 8_000 })
  } catch {
    const token = await gatewayLoginPassword(account, password)
    await fulfillLoginRoutes(page, token)
    await submitMobileLogin(page, '13800000000', DEV_SMS_CODE)
    await expect(page).toHaveURL(/\/(\?.*)?$/, { timeout: 20_000 })
  }
}

export async function loginViaMobileUI(page: Page, phone: string, code = DEV_SMS_CODE) {
  await submitMobileLogin(page, phone, code)
  try {
    await expect(page).toHaveURL(/\/(\?.*)?$/, { timeout: 8_000 })
  } catch {
    const token = await gatewayLoginMobile(phone, code)
    await fulfillLoginRoutes(page, token)
    await submitMobileLogin(page, phone, code)
    await expect(page).toHaveURL(/\/(\?.*)?$/, { timeout: 20_000 })
  }
}

export async function injectSession(page: Page, token: string) {
  await fulfillLoginRoutes(page, token)
  await submitMobileLogin(page, '13800000000', DEV_SMS_CODE)
  await expect(page).toHaveURL(/\/(\?.*)?$/, { timeout: 20_000 })
}

export async function loginUserForUI(page: Page, creds: { account?: string; password?: string; phone?: string; smsCode?: string }) {
  if (creds.phone) {
    await loginViaMobileUI(page, creds.phone, creds.smsCode)
    return
  }
  if (creds.account && creds.password) {
    await loginViaPasswordUI(page, creds.account, creds.password)
  }
}
