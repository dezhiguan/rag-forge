<template>
  <div class="auth-form">
        <h1 class="form-title">管理员登录</h1>
        <p class="form-sub">普通求职用户请前往 CareerMate</p>

        <div v-if="redirectNotice" class="tip tip-warn">
          登录会话已过期，登录后将自动跳回 <code>{{ redirectNotice }}</code>
        </div>

        <div class="tabs" role="tablist">
          <button
            type="button"
            class="tab"
            :class="{ active: tab === 'password' }"
            role="tab"
            :aria-selected="tab === 'password'"
            @click="switchTab('password')"
          >账号密码</button>
          <button
            type="button"
            class="tab"
            :class="{ active: tab === 'mobile' }"
            role="tab"
            :aria-selected="tab === 'mobile'"
            @click="switchTab('mobile')"
          >手机验证码</button>
        </div>

        <div v-if="errorMsg" class="tip tip-err">{{ errorMsg }}</div>

        <form v-if="tab === 'password'" @submit.prevent="handlePasswordLogin" novalidate>
          <div class="field">
            <label for="account">账号 / 手机号 / 邮箱</label>
            <input
              id="account"
              v-model.trim="form.account"
              type="text"
              autocomplete="username"
              placeholder="请输入登录账号"
              :disabled="loading"
              @input="errorMsg = ''"
            />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <input
              id="password"
              v-model="form.password"
              type="password"
              autocomplete="current-password"
              placeholder="至少 8 位,含字母与数字"
              :disabled="loading"
              @input="errorMsg = ''"
            />
          </div>
          <div class="row-between">
            <label class="check" :class="{ on: form.remember }">
              <input type="checkbox" v-model="form.remember" />
              <span class="box-tick" aria-hidden="true">{{ form.remember ? '✓' : '' }}</span>
              <span>记住我 30 天</span>
            </label>
            <a class="link" href="#" @click.prevent="onForgotPassword">忘记密码？</a>
          </div>
          <button type="submit" class="btn-primary" :disabled="loading">
            {{ loading ? '登录中…' : '登 录' }}
          </button>
        </form>

        <form v-else @submit.prevent="handleMobileLogin" novalidate>
          <div class="field">
            <label for="phone">手机号</label>
            <input
              id="phone"
              v-model.trim="form.phone"
              type="tel"
              autocomplete="tel"
              placeholder="+86 138 0000 0000"
              :disabled="loading"
              @input="errorMsg = ''"
            />
          </div>
          <div class="field">
            <label for="code">验证码</label>
            <div class="input-with-suffix">
              <input
                id="code"
                v-model.trim="form.smsCode"
                type="text"
                inputmode="numeric"
                maxlength="6"
                autocomplete="one-time-code"
                placeholder="请输入 6 位验证码"
                :disabled="loading"
                @input="errorMsg = ''"
              />
              <button
                type="button"
                class="sms-btn"
                :class="{ disabled: smsCountdown > 0 || sendingSms }"
                :disabled="smsCountdown > 0 || sendingSms"
                @click="handleSendSms"
              >{{ smsBtnLabel }}</button>
            </div>
          </div>
          <div class="row-between">
            <label class="check" :class="{ on: form.remember }">
              <input type="checkbox" v-model="form.remember" />
              <span class="box-tick" aria-hidden="true">{{ form.remember ? '✓' : '' }}</span>
              <span>记住此设备</span>
            </label>
            <span class="link muted">遇到问题？联系管理员</span>
          </div>
          <button type="submit" class="btn-primary" :disabled="loading">
            {{ loading ? '登录中…' : '登 录' }}
          </button>
        </form>

        <p class="footer-mini">登录即代表同意《管理员行为审计协议》</p>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../../composables/useAuth'
import { loginByPassword, loginByMobile, sendSmsCode } from '../../api/auth'

const route = useRoute()
const router = useRouter()
const { setSession } = useAuth()

const tab = ref('password')
const loading = ref(false)
const errorMsg = ref('')
const sendingSms = ref(false)
const smsCountdown = ref(0)
let countdownTimer = null

const form = reactive({
  account: '',
  password: '',
  phone: '',
  smsCode: '',
  remember: true,
})

const redirectNotice = computed(() => {
  return route.query.reason === 'expired' ? route.query.redirect || null : null
})

const smsBtnLabel = computed(() => {
  if (sendingSms.value) return '发送中…'
  if (smsCountdown.value > 0) return `${smsCountdown.value}s 后重发`
  return '获取验证码'
})

function switchTab(next) {
  if (loading.value) return
  tab.value = next
  errorMsg.value = ''
}

async function handlePasswordLogin() {
  if (loading.value) return
  if (!form.account || !form.password) {
    errorMsg.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    const res = await loginByPassword({
      account: form.account,
      password: form.password,
    })
    finishLogin(res)
  } catch (e) {
    errorMsg.value = e.message || '登录失败,请稍后重试'
  } finally {
    loading.value = false
  }
}

async function handleMobileLogin() {
  if (loading.value) return
  if (!form.phone || !form.smsCode) {
    errorMsg.value = '请输入手机号与验证码'
    return
  }
  loading.value = true
  try {
    const res = await loginByMobile({
      phone: form.phone,
      code: form.smsCode,
    })
    finishLogin(res)
  } catch (e) {
    errorMsg.value = e.message || '登录失败,请稍后重试'
  } finally {
    loading.value = false
  }
}

async function handleSendSms() {
  if (sendingSms.value || smsCountdown.value > 0) return
  if (!form.phone) {
    errorMsg.value = '请先输入手机号'
    return
  }
  sendingSms.value = true
  try {
    await sendSmsCode({ phone: form.phone, scene: 'login' })
    startCountdown(60)
  } catch (e) {
    errorMsg.value = e.message || '验证码发送失败'
  } finally {
    sendingSms.value = false
  }
}

function startCountdown(seconds) {
  smsCountdown.value = seconds
  countdownTimer = setInterval(() => {
    smsCountdown.value -= 1
    if (smsCountdown.value <= 0) {
      clearInterval(countdownTimer)
      countdownTimer = null
    }
  }, 1000)
}

function finishLogin({ accessToken, user }) {
  setSession(accessToken, user)
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect.startsWith('/') ? redirect : '/')
}

function onForgotPassword() {
  router.push('/auth/reset')
}

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>

<style scoped>
.auth-form {
  width: 100%;
  max-width: 380px;
}
.form-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}
.form-sub {
  font-size: 13px;
  color: #64748b;
  margin: 6px 0 0;
}

.tabs {
  display: flex;
  margin: 22px 0 18px;
  border-bottom: 1px solid #e2e8f0;
}
.tab {
  flex: 1;
  background: none;
  border: none;
  padding: 10px 0;
  font-size: 14px;
  color: #64748b;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color 0.15s, border-color 0.15s;
  font-family: inherit;
}
.tab:hover { color: #1e293b; }
.tab.active {
  color: #1d4ed8;
  border-bottom-color: #3b82f6;
  font-weight: 600;
}

.field { margin-bottom: 14px; }
.field label {
  font-size: 12px;
  color: #475569;
  display: block;
  margin-bottom: 6px;
}
.field input {
  width: 100%;
  height: 40px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 0 12px;
  font-size: 14px;
  color: #1e293b;
  background: #fff;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  font-family: inherit;
}
.field input:focus {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}
.field input::placeholder { color: #94a3b8; }
.field input:disabled { background: #f8fafc; color: #94a3b8; cursor: not-allowed; }

.input-with-suffix {
  display: flex;
  align-items: stretch;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-with-suffix:focus-within {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}
.input-with-suffix input {
  flex: 1;
  border: none;
  border-radius: 0;
  background: transparent;
}
.input-with-suffix input:focus { box-shadow: none; }
.sms-btn {
  border: none;
  border-left: 1px solid #e2e8f0;
  background: #fff;
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
  padding: 0 14px;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}
.sms-btn.disabled,
.sms-btn:disabled {
  color: #94a3b8;
  cursor: not-allowed;
}

.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  margin: 6px 0 16px;
}
.check {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #64748b;
  cursor: pointer;
  user-select: none;
}
.check input { display: none; }
.check .box-tick {
  width: 16px;
  height: 16px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #fff;
  background: #fff;
  transition: background 0.15s, border-color 0.15s;
}
.check.on .box-tick {
  background: #3b82f6;
  border-color: #3b82f6;
}
.link {
  color: #1d4ed8;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
}
.link:hover { text-decoration: underline; }
.link.muted { color: #64748b; font-weight: 400; cursor: default; }
.link.muted:hover { text-decoration: none; }

.btn-primary {
  width: 100%;
  height: 42px;
  background: linear-gradient(180deg, #3b82f6, #1d4ed8);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(29, 78, 216, 0.18);
  transition: filter 0.15s, transform 0.05s;
  font-family: inherit;
}
.btn-primary:hover:not(:disabled) { filter: brightness(1.05); }
.btn-primary:active:not(:disabled) { transform: translateY(1px); }
.btn-primary:disabled {
  background: linear-gradient(180deg, #93c5fd, #60a5fa);
  cursor: not-allowed;
  box-shadow: none;
}

.tip {
  padding: 9px 12px;
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 14px;
}
.tip code {
  font-family: ui-monospace, Menlo, monospace;
  background: rgba(0, 0, 0, 0.06);
  padding: 1px 5px;
  border-radius: 4px;
}
.tip-err {
  background: #fff1f0;
  color: #b42318;
  border: 1px solid #ffccc7;
}
.tip-warn {
  background: #fff7ed;
  color: #b54708;
  border: 1px solid #fed7aa;
}

.footer-mini {
  margin-top: 22px;
  text-align: center;
  font-size: 12px;
  color: #94a3b8;
}

</style>
