import { APIRequestContext, expect, request } from '@playwright/test'
import { RAG_API } from './env'
import { gatewayLoginMobile, gatewayLoginPassword } from './gateway'

export { RAG_API }

export async function ragLogin(account: string, password: string): Promise<string> {
  const ctx = await request.newContext()
  const res = await ctx.post(`${RAG_API}/auth/login`, {
    data: { account, password, remember: false },
  })
  if (res.ok()) {
    const body = await res.json()
    const token = body?.data?.access_token || body?.data?.accessToken || body?.access_token
    if (token) return token as string
  }
  return gatewayLoginPassword(account, password)
}

export async function ragLoginMobile(phone: string, code: string): Promise<string> {
  const ctx = await request.newContext()
  const res = await ctx.post(`${RAG_API}/auth/login-mobile`, {
    data: { phone, code },
  })
  if (res.ok()) {
    const body = await res.json()
    const token = body?.data?.access_token || body?.data?.accessToken || body?.access_token
    if (token) return token as string
  }
  return gatewayLoginMobile(phone, code)
}

export async function asUser(token: string): Promise<APIRequestContext> {
  return request.newContext({
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  })
}

export function unwrapList(body: unknown): unknown[] {
  const data = (body as { data?: unknown })?.data ?? body
  if (Array.isArray(data)) return data
  const records = (data as { records?: unknown[] })?.records
  return Array.isArray(records) ? records : []
}

export async function expectOkJson(api: APIRequestContext, method: 'get' | 'post' | 'put' | 'delete', url: string, data?: unknown) {
  const res = await api[method](url, data ? { data } : undefined)
  expect(res.ok(), await res.text()).toBeTruthy()
  return res.json()
}
