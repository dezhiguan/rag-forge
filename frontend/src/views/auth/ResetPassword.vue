<template>
  <div class="auth-form reset-form">
    <div class="step-meter" aria-label="密码重置进度">
      <span v-for="item in 3" :key="item" :class="{ active: step >= item }" />
    </div>

    <div v-if="notice" class="tip tip-ok">{{ notice }}</div>

    <template v-if="step === 1">
      <h1 class="form-title">验证身份</h1>
      <p class="form-sub">填写账号（或邮箱）与绑定手机号，系统会发送一次性验证码。</p>

      <div v-if="errorMsg" class="tip tip-err">{{ errorMsg }}</div>

      <form @submit.prevent="handleInit" novalidate>
        <div class="field">
          <label for="account">账号 / 邮箱</label>
          <input id="account" v-model.trim="form.account" type="text" autocomplete="username" placeholder="请输入账号或邮箱" />
        </div>
        <div class="field">
          <label for="phone">手机号</label>
          <input id="phone" v-model.trim="form.phone" type="tel" autocomplete="tel" placeholder="用于接收验证码" />
        </div>
        <button type="submit" class="btn-primary" :disabled="loading">{{ loading ? '发送中...' : '发送验证码' }}</button>
      </form>
    </template>

    <template v-else-if="step === 2">
      <h1 class="form-title">设置新密码</h1>
      <p class="form-sub">验证码通过后，新密码会立即用于 RAGForge 登录。</p>

      <div v-if="errorMsg" class="tip tip-err">{{ errorMsg }}</div>

      <form @submit.prevent="handleConfirm" novalidate>
        <div class="field">
          <label for="smsCode">验证码</label>
          <input id="smsCode" v-model.trim="form.code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="请输入 6 位验证码" />
        </div>
        <div class="field">
          <label for="newPassword">新密码</label>
          <input id="newPassword" v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="至少 8 位,含字母与数字" />
        </div>
        <div class="field">
          <label for="confirmPassword">确认新密码</label>
          <input id="confirmPassword" v-model="form.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入新密码" />
        </div>
        <button type="submit" class="btn-primary" :disabled="loading">{{ loading ? '提交中...' : '确认重置并登录' }}</button>
      </form>
    </template>

    <template v-else>
      <div class="success-mark">✓</div>
      <h1 class="form-title">密码已更新</h1>
      <p class="form-sub">正在进入 RAGForge 管理后台。</p>
    </template>

    <button v-if="step < 3" type="button" class="text-btn" @click="router.push('/login')">返回登录</button>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { resetPasswordConfirm, resetPasswordInit, resetPasswordVerify } from '../../api/auth'
import { notifyLoggedOut, silentRefresh } from '../../api/session'
import { loadMe } from '../../api/account'
import { useAuth } from '../../composables/useAuth'

const router = useRouter()
const { state, setResetTicket, clearResetTicket } = useAuth()

const step = ref(1)
const loading = ref(false)
const notice = ref('')
const errorMsg = ref('')

const form = reactive({
  account: '',
  phone: '',
  code: '',
  newPassword: '',
  confirmPassword: '',
})

async function handleInit() {
  if (loading.value) return
  if (!form.account || !form.phone) {
    errorMsg.value = '请输入账号和手机号'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    await resetPasswordInit({ account: form.account, phone: form.phone })
    notice.value = '如果账号信息匹配，验证码将发送到绑定手机号。'
    step.value = 2
  } catch (e) {
    // 限流/手机号格式/短信服务故障/网络异常必须留在本步明确提示；
    // "账号是否存在/是否匹配"后端恒定返回成功，不会走到这里，无枚举泄露
    errorMsg.value = e?.message || '验证码发送失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function handleConfirm() {
  if (loading.value) return
  if (!form.code || !form.newPassword || !form.confirmPassword) {
    errorMsg.value = '请填写验证码和新密码'
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    errorMsg.value = '两次输入的新密码不一致'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const verify = await resetPasswordVerify({ account: form.account, phone: form.phone, code: form.code })
    const ticket = verify?.data?.reset_ticket || verify?.data?.resetTicket || verify?.reset_ticket || verify?.resetTicket
    if (!ticket) {
      throw new Error('reset ticket missing')
    }
    setResetTicket(ticket)
    await resetPasswordConfirm({
      account: form.account,
      reset_ticket: state.resetTicket,
      new_password: form.newPassword,
    })
    clearResetTicket()
    // 改密会灭族全部旧会话并 bump session_version：confirm 返回的 token 一出生即过期。
    // 先复位残留的续期定时器（并让其他标签页下线），再走 silentRefresh 的跨标签页
    // 单飞锁换新 token——裸调 refresh 会与定时续期双刷，触发网关重放检测，
    // 正是"重置成功后偶发提示登录已过期"的根因。
    notifyLoggedOut()
    step.value = 3
    let sessionReady = false
    try {
      await silentRefresh()
      await loadMe() // 用新 token 补全用户信息后再进主页，避免半登录态
      sessionReady = true
    } catch {
      sessionReady = false
    }
    if (sessionReady) {
      setTimeout(() => router.replace('/'), 450)
    } else {
      // 换不到有效会话就不硬闯主页（否则必弹"登录已过期"），引导用新密码登录。
      // sessionStorage 标记兜底：整页跳转会被入口 Nginx 302 剥掉 query。
      sessionStorage.setItem('rf_reset_success', '1')
      setTimeout(() => router.replace('/login?reason=reset-success'), 450)
    }
  } catch (e) {
    errorMsg.value = e.message === 'reset ticket missing' ? '验证码已失效，请重新获取' : (e.message || '密码重置失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reset-form {
  width: 100%;
  max-width: 380px;
}

.step-meter {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 22px;
}

.step-meter span {
  height: 4px;
  border-radius: 999px;
  background: #e2e8f0;
}

.step-meter span.active {
  background: #0ea5e9;
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
  margin: 6px 0 20px;
}

.field {
  margin-bottom: 14px;
}

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
  border-color: #0ea5e9;
  box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.12);
}

.btn-primary {
  width: 100%;
  height: 42px;
  background: linear-gradient(180deg, #0ea5e9, #0369a1);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-family: inherit;
}

.btn-primary:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}

.text-btn {
  width: 100%;
  margin-top: 18px;
  border: none;
  background: transparent;
  color: #0369a1;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
}

.tip {
  padding: 9px 12px;
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 14px;
}

.tip-err {
  background: #fff1f0;
  color: #b42318;
  border: 1px solid #ffccc7;
}

.tip-ok {
  background: #ecfdf3;
  color: #027a48;
  border: 1px solid #abefc6;
}

.success-mark {
  width: 52px;
  height: 52px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
  background: #dcfce7;
  color: #15803d;
  font-size: 28px;
  font-weight: 800;
}
</style>
