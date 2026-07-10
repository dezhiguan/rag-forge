<template>
  <div class="auth-form">
    <h1 class="form-title">注册 RAGForge</h1>
    <p class="form-sub">已有账号？<a class="link" href="#" @click.prevent="goLogin">返回登录</a></p>

    <div v-if="errorMsg" class="tip tip-err">{{ errorMsg }}</div>
    <div v-if="successMsg" class="tip tip-ok">{{ successMsg }}</div>

    <form @submit.prevent="handleRegister" novalidate>
      <div class="field">
        <label for="r-username">用户名</label>
        <input id="r-username" v-model.trim="form.username" type="text" autocomplete="username"
          placeholder="2-32 位中文、字母、数字或下划线" :disabled="loading" @input="errorMsg = ''" />
      </div>
      <div class="field">
        <label for="r-email">邮箱（选填）</label>
        <input id="r-email" v-model.trim="form.email" type="email" autocomplete="email"
          placeholder="you@example.com" :disabled="loading" @input="errorMsg = ''" />
      </div>
      <div class="field">
        <label for="r-password">密码</label>
        <input id="r-password" v-model="form.password" type="password" autocomplete="new-password"
          placeholder="至少 8 位，含字母与数字" :disabled="loading" @input="errorMsg = ''" />
        <div v-if="form.password" class="pwd-strength">
          <div class="pwd-strength-bar">
            <div class="pwd-strength-fill" :style="{ width: pwdStrength.pct + '%', background: pwdStrength.color }"></div>
          </div>
          <span class="pwd-strength-label" :style="{ color: pwdStrength.color }">{{ pwdStrength.label }}</span>
        </div>
      </div>
      <div class="field">
        <label for="r-password2">确认密码</label>
        <input id="r-password2" v-model="form.confirmPassword" type="password" autocomplete="new-password"
          placeholder="请再次输入密码" :disabled="loading" @input="errorMsg = ''" />
      </div>
      <div class="field">
        <label for="r-phone">手机号（必填，需短信验证）</label>
        <input id="r-phone" v-model.trim="form.phone" type="tel" placeholder="请输入手机号"
          :disabled="loading" @input="errorMsg = ''" />
      </div>
      <div class="field">
        <label for="r-code">短信验证码</label>
        <div class="input-with-suffix">
          <input id="r-code" v-model.trim="form.smsCode" type="text" inputmode="numeric" maxlength="6"
            placeholder="6 位验证码" :disabled="loading" @input="errorMsg = ''" />
          <button type="button" class="sms-btn" :class="{ disabled: smsCountdown > 0 || sendingSms }"
            :disabled="smsCountdown > 0 || sendingSms" @click="handleSendSms">{{ smsBtnLabel }}</button>
        </div>
      </div>
      <div class="field terms-row">
        <label class="terms-label">
          <input type="checkbox" v-model="agreeTerms" class="terms-checkbox" />
          <span>我已阅读并同意 <a class="link" href="#" @click.prevent="legalDoc = 'terms'">用户协议</a> 和 <a class="link" href="#" @click.prevent="legalDoc = 'privacy'">隐私政策</a></span>
        </label>
      </div>
      <button type="submit" class="btn-primary" :disabled="loading || !agreeTerms">{{ loading ? '提交中…' : '注 册' }}</button>
    </form>

    <LegalDocModal :which="legalDoc" @close="legalDoc = ''" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { sendSmsCode } from '../../api/auth'
import { register } from '../../api/account'
import LegalDocModal from '../../components/LegalDocModal.vue'

const router = useRouter()
const legalDoc = ref('')
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const sendingSms = ref(false)
const smsCountdown = ref(0)
let countdownTimer = null

const agreeTerms = ref(false)
const form = reactive({ phone: '', smsCode: '', username: '', email: '', password: '', confirmPassword: '' })

const pwdStrength = computed(() => {
  const p = form.password
  if (!p) return { pct: 0, label: '', color: '' }
  // 与后端注册规则对齐：基线=至少 8 位且同时含字母和数字；未达基线一律"弱"（提交会被拒，不给"中"的误导）。
  const meetsBaseline = p.length >= 8 && /[a-zA-Z]/.test(p) && /[0-9]/.test(p)
  if (!meetsBaseline) return { pct: 25, label: '弱', color: '#dc2626' }
  // 达基线后按增强项升级：大小写混合 / 含特殊字符 / 长度≥12，满足 2 项及以上为"强"。
  let bonus = 0
  if (/[A-Z]/.test(p) && /[a-z]/.test(p)) bonus++
  if (/[^A-Za-z0-9]/.test(p)) bonus++
  if (p.length >= 12) bonus++
  if (bonus >= 2) return { pct: 100, label: '强', color: '#059669' }
  return { pct: 60, label: '中', color: '#d97706' }
})

const smsBtnLabel = computed(() => {
  if (sendingSms.value) return '发送中…'
  if (smsCountdown.value > 0) return `${smsCountdown.value}s 后重发`
  return '获取验证码'
})

async function handleSendSms() {
  if (sendingSms.value || smsCountdown.value > 0) return
  if (!form.phone) {
    errorMsg.value = '请先输入手机号'
    return
  }
  sendingSms.value = true
  try {
    await sendSmsCode({ phone: form.phone, scene: 'register' })
    startCountdown(60)
  } catch (e) {
    errorMsg.value = e.message || '验证码发送失败'
  } finally {
    sendingSms.value = false
  }
}

async function handleRegister() {
  if (loading.value) return
  if (!form.phone || !form.smsCode) {
    errorMsg.value = '请填写手机号与验证码'
    return
  }
  if (!form.username && !form.email) {
    errorMsg.value = '请至少填写用户名或邮箱'
    return
  }
  if (form.password || form.confirmPassword) {
    if (form.password !== form.confirmPassword) {
      errorMsg.value = '两次输入的密码不一致'
      return
    }
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const body = await register({
      phone: form.phone,
      smsCode: form.smsCode,
      username: form.username || undefined,
      email: form.email || undefined,
      password: form.password || undefined,
      termsVersion: '1.0',
    })
    const data = body?.data ?? body
    successMsg.value = data?.linked
      ? '该手机号已注册，已为你关联并补全账号信息，正在跳转登录…'
      : '注册成功，正在跳转登录…'
    setTimeout(() => router.replace('/login'), 1200)
  } catch (e) {
    errorMsg.value = e.message || '注册失败，请稍后重试'
  } finally {
    loading.value = false
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

function goLogin() {
  router.push('/login')
}

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>

<style scoped>
.auth-form { width: 100%; max-width: 380px; }
.form-title { font-size: 22px; font-weight: 700; color: #0f172a; margin: 0; }
.form-sub { font-size: 13px; color: #64748b; margin: 6px 0 18px; }
.field { margin-bottom: 14px; }
.field label { font-size: 12px; color: #475569; display: block; margin-bottom: 6px; }
.field input {
  width: 100%; height: 40px; border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 0 12px; font-size: 14px; color: #1e293b; background: #fff; outline: none; font-family: inherit;
}
.field input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.input-with-suffix {
  display: flex; align-items: stretch; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; background: #fff;
}
.input-with-suffix input { flex: 1; border: none; border-radius: 0; background: transparent; height: 40px; padding: 0 12px; }
.sms-btn {
  border: none; border-left: 1px solid #e2e8f0; background: #fff; color: #1d4ed8;
  font-size: 13px; font-weight: 600; padding: 0 14px; cursor: pointer; font-family: inherit; white-space: nowrap;
}
.sms-btn.disabled, .sms-btn:disabled { color: #94a3b8; cursor: not-allowed; }
.btn-primary {
  width: 100%; height: 42px; background: linear-gradient(180deg, var(--primary), #1d4ed8); color: #fff;
  font-weight: 600; font-size: 14px; border: none; border-radius: 8px; cursor: pointer; margin-top: 4px; font-family: inherit;
}
.btn-primary:disabled { background: linear-gradient(180deg, #93c5fd, #60a5fa); cursor: not-allowed; }
.link { color: #1d4ed8; font-weight: 600; text-decoration: none; cursor: pointer; }
.link:hover { text-decoration: underline; }
.tip { padding: 9px 12px; border-radius: 6px; font-size: 13px; margin-bottom: 14px; }
.tip-err { background: #fff1f0; color: #b42318; border: 1px solid #ffccc7; }
.tip-ok { background: #f0fdf4; color: #166534; border: 1px solid #bbf7d0; }
.pwd-strength { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.pwd-strength-bar { flex: 1; height: 4px; background: #e2e8f0; border-radius: 2px; overflow: hidden; }
.pwd-strength-fill { height: 100%; border-radius: 2px; transition: width .3s, background .3s; }
.pwd-strength-label { font-size: 11px; font-weight: 600; flex-shrink: 0; }
.terms-row { margin-top: 4px; margin-bottom: 16px; }
.terms-label { display: flex; align-items: flex-start; gap: 8px; cursor: pointer; font-size: 13px; color: #475569; }
.terms-checkbox { margin-top: 2px; flex-shrink: 0; cursor: pointer; }
</style>
