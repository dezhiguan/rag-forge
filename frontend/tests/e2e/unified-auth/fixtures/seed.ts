import { gatewayRegister } from './gateway'
import { asUser, ragLoginMobile, RAG_API } from './api'
import { ADMIN, U_CM, U_OTHER, U_RAG } from './accounts'

async function createPrivateKb(token: string, name: string) {
  const api = await asUser(token)
  const res = await api.post(`${RAG_API}/v1/kb`, {
    data: { name, description: 'unified-auth QA seed', chunkSize: 256, chunkOverlap: 24 },
  })
  if (!res.ok()) return null
  const body = await res.json()
  return body?.data?.id ?? body?.id ?? null
}

export async function ensureQaAccounts() {
  const users = [U_RAG, U_OTHER]
  for (const u of users) {
    const { res, json } = await gatewayRegister({
      phone: u.phone,
      smsCode: u.smsCode,
      username: u.username,
      email: u.email,
      password: u.password,
    })
    if (res.ok || json?.linked) continue
    const msg = String(json?.message || json?.error || '')
    if (/占用|已注册|exists|duplicate/i.test(msg)) continue
    throw new Error(`seed ${u.alias} failed: ${res.status} ${JSON.stringify(json)}`)
  }

  const cm = await gatewayRegister({
    phone: U_CM.phone,
    smsCode: U_CM.smsCode,
    app: 'careermate',
  })
  if (!cm.res.ok && !cm.json?.linked) {
    const msg = String(cm.json?.message || cm.json?.error || '')
    if (!/占用|已注册|exists|duplicate/i.test(msg)) {
      // careermate-only register may differ; ignore if exists
    }
  }

  const tRag = await ragLoginMobile(U_RAG.phone, U_RAG.smsCode)
  const tOther = await ragLoginMobile(U_OTHER.phone, U_OTHER.smsCode)
  await createPrivateKb(tRag, `qa-kb-own-${U_RAG.username}`)
  await createPrivateKb(tOther, `qa-kb-other-${U_OTHER.username}`)

  return { ADMIN, U_RAG, U_OTHER, U_CM }
}
