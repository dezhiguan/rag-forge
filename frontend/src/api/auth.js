const MOCK_DELAY_MS = 600

const MOCK_USER = {
  id: 26,
  account: 'admin',
  password: 'Admin123!',
  phone: '+8613800000000',
  smsCode: '123456',
  displayName: 'guandezhi@ragforge.cn',
  ragRole: 'ADMIN',
  tenantSlug: 'personal-26',
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function mockToken() {
  return 'mock-access-' + Math.random().toString(36).slice(2, 10)
}

function userProfile() {
  return {
    userId: MOCK_USER.id,
    displayName: MOCK_USER.displayName,
    ragRole: MOCK_USER.ragRole,
    tenantSlug: MOCK_USER.tenantSlug,
  }
}

export async function loginByPassword({ account, password }) {
  await delay(MOCK_DELAY_MS)
  if (!account || !password) {
    throw { code: 'INVALID_INPUT', message: '请输入账号和密码' }
  }
  if (account !== MOCK_USER.account || password !== MOCK_USER.password) {
    throw { code: 'INVALID_CREDENTIALS', message: '账号或密码错误' }
  }
  return { accessToken: mockToken(), user: userProfile() }
}

export async function loginByMobile({ phone, code }) {
  await delay(MOCK_DELAY_MS)
  if (!phone || !code) {
    throw { code: 'INVALID_INPUT', message: '请输入手机号与验证码' }
  }
  if (phone !== MOCK_USER.phone || code !== MOCK_USER.smsCode) {
    throw { code: 'INVALID_CREDENTIALS', message: '手机号或验证码错误' }
  }
  return { accessToken: mockToken(), user: userProfile() }
}

export async function sendSmsCode({ phone, scene }) {
  await delay(300)
  if (!phone || !/^\+?\d{8,}$/.test(phone.replace(/\s/g, ''))) {
    throw { code: 'INVALID_PHONE', message: '手机号格式不正确' }
  }
  return { sent: true, scene, expiresIn: 300 }
}
