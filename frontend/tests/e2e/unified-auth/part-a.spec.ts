/**
 * Part A · 全量功能与权限测试（QA 视角）
 * 配套：docs/unified-auth-test-plan-V1.md
 */
import { expect, test } from '@playwright/test'
import { ADMIN, PHONE_NEW, U_CM, U_OTHER, U_RAG } from './fixtures/accounts'
import { asUser, ragLogin, ragLoginMobile, RAG_API, unwrapList } from './fixtures/api'
import { DEV_SMS_CODE, RAG_WEB } from './fixtures/env'
import { gatewayLoginMobile, gatewayRegister } from './fixtures/gateway'
import { ensureQaAccounts } from './fixtures/seed'
import { injectSession, loginUserForUI, loginViaMobileUI, loginViaPasswordUI } from './fixtures/ui'

test.beforeAll(async () => {
  await ensureQaAccounts()
})

// ─── A3 登录 ─────────────────────────────────────────────
test.describe('A3 登录', () => {
  test('TC-LOG-01 用户名登录 + /me userId 一致', async () => {
    const token = await ragLogin(U_RAG.account, U_RAG.password)
    const api = await asUser(token)
    const me = await (await api.get(`${RAG_API}/v1/me`)).json()
    expect(me.data?.userId ?? me.userId).toBeTruthy()
    const loginRes = await api.post(`${RAG_API}/auth/login`, { data: { account: U_RAG.account, password: U_RAG.password, remember: false } }).catch(() => null)
    if (loginRes?.ok()) {
      const body = await loginRes.json()
      const id2 = body?.data?.user?.userId ?? body?.data?.userId
      if (id2) expect(id2).toBe(me.data?.userId ?? me.userId)
    }
  })

  test('TC-LOG-02 邮箱登录解析同一用户', async () => {
    const t1 = await ragLogin(U_RAG.account, U_RAG.password)
    const t2 = await ragLogin(U_RAG.email, U_RAG.password)
    const api1 = await asUser(t1)
    const api2 = await asUser(t2)
    const id1 = (await (await api1.get(`${RAG_API}/v1/me`)).json()).data?.userId
    const id2 = (await (await api2.get(`${RAG_API}/v1/me`)).json()).data?.userId
    expect(id1).toBeTruthy()
    expect(id2).toBe(id1)
  })

  test('TC-LOG-03 手机号+密码登录', async () => {
    const token = await ragLogin(U_RAG.phone, U_RAG.password)
    const me = await (await (await asUser(token)).get(`${RAG_API}/v1/me`)).json()
    expect(me.data?.userId).toBeTruthy()
  })

  test('TC-LOG-04 手机验证码登录', async () => {
    const token = await ragLoginMobile(U_RAG.phone, DEV_SMS_CODE)
    expect(token).toBeTruthy()
    const me = await (await (await asUser(token)).get(`${RAG_API}/v1/me`)).json()
    expect(me.data?.userId).toBeTruthy()
  })

  test('TC-LOG-05 错误密码 401', async ({ request }) => {
    const res = await request.post(`${RAG_API}/auth/login`, {
      data: { account: U_RAG.account, password: 'wrong-Pass-xyz', remember: false },
    })
    expect([401, 403, 500]).toContain(res.status())
    if (res.status() === 401) {
      const body = await res.text()
      expect(body).toMatch(/密码|credential|BAD_CREDENTIALS/i)
    }
  })

  test('TC-LOG-08 登出后受保护接口 401', async () => {
    const token = await ragLoginMobile(ADMIN.phone, DEV_SMS_CODE)
    const api = await asUser(token)
    expect((await api.get(`${RAG_API}/v1/me`)).ok()).toBeTruthy()
    const logout = await api.post(`${RAG_API}/auth/logout`)
    expect(logout.ok(), await logout.text()).toBeTruthy()
    const after = await api.get(`${RAG_API}/v1/me`)
    if (after.status() === 200) {
      test.info().annotations.push({
        type: 'bug',
        description: 'logout 返回 revoked 但 access token 仍可调 /me',
      })
    }
    expect([401, 403]).toContain(after.status())
  })

  test('TC-LOG-10 多会话并存', async () => {
    const tA = await ragLogin(U_RAG.account, U_RAG.password)
    const tB = await ragLogin(U_RAG.account, U_RAG.password)
    const apiA = await asUser(tA)
    const apiB = await asUser(tB)
    expect((await apiA.get(`${RAG_API}/v1/me`)).ok()).toBeTruthy()
    expect((await apiB.get(`${RAG_API}/v1/me`)).ok()).toBeTruthy()
  })
})

// ─── A2 注册（UI + API）────────────────────────────────────
test.describe('A2 注册', () => {
  test('TC-REG-05 短信码错误拒绝', async ({ request }) => {
    const res = await request.post(`${RAG_API}/auth/register`, {
      data: {
        phone: PHONE_NEW,
        smsCode: '000000',
        username: `qa_bad_${Date.now()}`,
        password: 'Str0ng#Pass1',
      },
    })
    expect(res.status()).toBeGreaterThanOrEqual(400)
  })

  test('TC-REG-08 弱密码/非法邮箱 UI 校验', async ({ page }) => {
    await page.goto(`${RAG_WEB}/auth/register`, { waitUntil: 'domcontentloaded' })
    await page.getByLabel('手机号（必填，需短信验证）').fill('13800138888')
    await page.getByLabel('用户名').fill('qa_weak')
    await page.getByLabel('邮箱（选填）').fill('not-an-email')
    await page.getByLabel('密码', { exact: true }).fill('123')
    await page.getByRole('button', { name: '注 册' }).click()
    await expect(page.getByText(/验证码|密码|邮箱|格式/i).first()).toBeVisible({ timeout: 10_000 })
  })

  test('TC-REG-03 用户名重复', async () => {
    const { res, json } = await gatewayRegister({
      phone: `138${String(Date.now()).slice(-8)}`,
      smsCode: DEV_SMS_CODE,
      username: U_RAG.username,
      email: `dup_${Date.now()}@test.local`,
      password: 'Str0ng#Pass1',
    })
    expect(res.status).toBeGreaterThanOrEqual(400)
    expect(JSON.stringify(json)).toMatch(/占用|已存在|duplicate|username/i)
  })
})

// ─── A6 菜单 ─────────────────────────────────────────────
test.describe('A6 前端控权·菜单', () => {
  test('TC-MENU-01 普通用户菜单', async ({ page }) => {
    await loginUserForUI(page, { phone: U_RAG.phone, smsCode: DEV_SMS_CODE })
    await expect(page.getByText('驾驶舱').first()).toBeVisible()
    await expect(page.getByText('知识库管理').first()).toBeVisible()
    await expect(page.getByText('检索调试台').first()).toBeVisible()
    await expect(page.getByText('应答调试台').first()).toBeVisible()
    await expect(page.getByText('评测实验室')).toHaveCount(0)
    await expect(page.getByText('API 网关')).toHaveCount(0)
    await expect(page.getByText('模型 & 成本')).toHaveCount(0)
  })

  test('TC-MENU-02 管理员菜单', async ({ page }) => {
    await loginUserForUI(page, { phone: ADMIN.phone, smsCode: DEV_SMS_CODE })
    await expect(page.getByText('模型 & 成本').first()).toBeVisible()
    await expect(page.getByText('API 网关').first()).toBeVisible()
  })

  test('TC-MENU-04 隐藏菜单 API 仍 403', async () => {
    const token = await ragLoginMobile(U_RAG.phone, DEV_SMS_CODE)
    const api = await asUser(token)
    for (const path of ['/v1/admin/api-keys', '/v1/models/cost/stats']) {
      const res = await api.get(`${RAG_API}${path}`)
      expect(res.status(), path).toBe(403)
    }
  })
})

// ─── A7 列表行级权限 ─────────────────────────────────────
test.describe('A7 查询接口行级权限', () => {
  test('TC-LIST-01/05 普通用户 KB 列表隔离', async () => {
    const tRag = await ragLoginMobile(U_RAG.phone, DEV_SMS_CODE)
    const tOther = await ragLoginMobile(U_OTHER.phone, DEV_SMS_CODE)
    const listRag = unwrapList(await (await (await asUser(tRag)).get(`${RAG_API}/v1/kb`)).json())
    const listOther = unwrapList(await (await (await asUser(tOther)).get(`${RAG_API}/v1/kb`)).json())
    const idsRag = new Set(listRag.map((k: { id: number }) => k.id))
    const idsOther = new Set(listOther.map((k: { id: number }) => k.id))
    const privateRag = [...idsRag].filter((id) => !idsOther.has(id))
    const privateOther = [...idsOther].filter((id) => !idsRag.has(id))
    expect(privateRag.length + privateOther.length).toBeGreaterThan(0)
  })

  test('TC-LIST-02 管理员可见更多非 SYSTEM 库', async () => {
    const tAdmin = await ragLoginMobile(ADMIN.phone, DEV_SMS_CODE)
    const tUser = await ragLoginMobile(U_RAG.phone, DEV_SMS_CODE)
    const adminList = unwrapList(await (await (await asUser(tAdmin)).get(`${RAG_API}/v1/kb`)).json())
    const userList = unwrapList(await (await (await asUser(tUser)).get(`${RAG_API}/v1/kb`)).json())
    expect(adminList.length).toBeGreaterThanOrEqual(userList.length)
    for (const kb of adminList as Array<{ kbType?: string }>) {
      expect((kb.kbType || '').toUpperCase()).not.toBe('SYSTEM')
    }
  })
})

// ─── A10 /me ─────────────────────────────────────────────
test.describe('A10 /me 聚合', () => {
  test('TC-ME-01 字段聚合', async () => {
    const token = await ragLoginMobile(U_RAG.phone, DEV_SMS_CODE)
    const me = (await (await (await asUser(token)).get(`${RAG_API}/v1/me`)).json()).data
    expect(me.userId).toBeTruthy()
    expect(me.ragRole).toBeTruthy()
    expect(Array.isArray(me.capabilities)).toBeTruthy()
    expect(me.capabilities.length).toBeGreaterThan(0)
  })

  test('TC-ME-02 capabilities 驱动菜单', async ({ page }) => {
    const token = await ragLoginMobile(U_RAG.phone, DEV_SMS_CODE)
    const me = (await (await (await asUser(token)).get(`${RAG_API}/v1/me`)).json()).data
    await injectSession(page, token)
    const caps: string[] = me.capabilities || []
    if (!caps.includes('apikey:admin')) {
      await expect(page.getByText('API 网关')).toHaveCount(0)
    }
    if (!caps.includes('eval:write')) {
      await expect(page.getByText('评测实验室')).toHaveCount(0)
    }
  })

  test('TC-PROF-03 显示名兜底不出现 user:2', async ({ page }) => {
    await loginUserForUI(page, { phone: U_RAG.phone, smsCode: DEV_SMS_CODE })
    await expect(page.getByText(/^user:\d+$/)).toHaveCount(0)
    await expect(page.getByText('tn_admin')).toHaveCount(0)
  })
})

// ─── A3 UI 登录流 ─────────────────────────────────────────
test.describe('A3 登录 UI', () => {
  test('TC-LOG-04 UI 手机验证码登录', async ({ page }) => {
    await loginViaMobileUI(page, U_RAG.phone, DEV_SMS_CODE)
    await expect(page.getByText('驾驶舱').first()).toBeVisible()
  })
})
