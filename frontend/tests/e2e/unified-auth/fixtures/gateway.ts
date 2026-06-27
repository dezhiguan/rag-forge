import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { GW_BASE } from './env'

const ASSERTION_TYPE = 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer'
const CLIENT_ID = process.env.AUTH_CLIENT_ID || 'ragforge-admin-backend'
const TARGET_AUD = process.env.AUTH_TARGET_AUD || 'ragforge-admin-api'
const TOKEN_AUD = process.env.AUTH_TOKEN_ENDPOINT_AUDIENCE || 'https://auth.careermate.cn/oauth/token'

function resolvePrivateKeyPem(): string {
  const fromEnv = process.env.AUTH_CLIENT_ASSERTION_KEY_PEM
  if (fromEnv) return fromEnv.replace(/\\n/g, '\n')
  const here = path.dirname(fileURLToPath(import.meta.url))
  const candidates = [
    path.resolve(here, '../../../../../../auth-gateway/config/keys/auth-active.pem'),
    path.resolve(here, '../../../../../auth-gateway/config/keys/auth-active.pem'),
    '/Users/amy/CursorProject/auth-gateway/config/keys/auth-active.pem',
  ]
  for (const p of candidates) {
    if (fs.existsSync(p)) return fs.readFileSync(p, 'utf8')
  }
  throw new Error('auth-active.pem not found; set AUTH_CLIENT_ASSERTION_KEY_PEM')
}

export function createClientAssertion(clientId = CLIENT_ID): string {
  const privateKey = resolvePrivateKeyPem()
  const now = Math.floor(Date.now() / 1000)
  const header = { alg: 'RS256', typ: 'JWT', kid: process.env.AUTH_CLIENT_ASSERTION_KID || 'auth-active' }
  const payload = {
    iss: clientId,
    sub: clientId,
    aud: TOKEN_AUD,
    jti: `ca_${crypto.randomUUID()}`,
    iat: now,
    exp: now + 600,
  }
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(payload))}`
  const signature = crypto.createSign('RSA-SHA256').update(signingInput).sign(privateKey, 'base64url')
  return `${signingInput}.${signature}`
}

function base64url(input: string) {
  return Buffer.from(input).toString('base64url')
}

async function gatewayForm(path: string, fields: Record<string, string>) {
  const body = new URLSearchParams({
    client_id: CLIENT_ID,
    client_assertion_type: ASSERTION_TYPE,
    client_assertion: createClientAssertion(),
    target_aud: TARGET_AUD,
    ...fields,
  })
  const res = await fetch(`${GW_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  const text = await res.text()
  let json: Record<string, unknown> = {}
  try {
    json = JSON.parse(text)
  } catch {
    json = { message: text }
  }
  return { res, json, text }
}

export async function gatewayLoginPassword(account: string, password: string): Promise<string> {
  const { res, json } = await gatewayForm('/auth/login/password', { account, password })
  if (!res.ok) throw new Error(`gateway password login failed: ${res.status} ${JSON.stringify(json)}`)
  const token = (json.access_token as string) || ''
  if (!token) throw new Error('gateway login missing access_token')
  return token
}

export async function gatewayLoginMobile(phone: string, code: string): Promise<string> {
  const { res, json } = await gatewayForm('/auth/login/mobile', { phone, code })
  if (!res.ok) throw new Error(`gateway mobile login failed: ${res.status} ${JSON.stringify(json)}`)
  const token = (json.access_token as string) || ''
  if (!token) throw new Error('gateway mobile login missing access_token')
  return token
}

export async function gatewayRegister(payload: {
  phone: string
  smsCode: string
  username?: string
  email?: string
  password?: string
  app?: string
}) {
  const res = await fetch(`${GW_BASE}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ app: 'ragforge', ...payload }),
  })
  const json = await res.json()
  return { res, json }
}
