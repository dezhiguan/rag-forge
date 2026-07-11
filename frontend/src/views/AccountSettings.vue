<template>
  <div class="page-body">
    <div class="account-page">
      <div class="tabs" role="tablist">
        <button class="tab" :class="{ active: tab === 'profile' }" @click="tab = 'profile'">个人设置</button>
        <button class="tab" :class="{ active: tab === 'security' }" @click="tab = 'security'">安全中心</button>
      </div>

      <!-- 个人设置：RAG 本地资料，不调网关 -->
      <section v-if="tab === 'profile'" class="card card-pad">
        <p class="card-hint">个人资料仅影响 RAGForge 内的展示，不会同步到其它应用。</p>
        <div class="field">
          <label>显示名</label>
          <input class="input" v-model.trim="profile.displayName" type="text" placeholder="用于展示的名称" />
        </div>
        <div class="field">
          <label>头像 URL</label>
          <input class="input" v-model.trim="profile.avatar" type="text" placeholder="https://..." />
        </div>
        <div class="field">
          <label>个人简介</label>
          <textarea class="input" v-model.trim="profile.bio" rows="3" placeholder="一句话介绍自己"></textarea>
        </div>
        <button class="btn btn-primary" :disabled="savingProfile" @click="saveProfile">
          {{ savingProfile ? '保存中…' : '保存' }}
        </button>
      </section>

      <!-- 安全中心：凭证统一调网关 -->
      <section v-else class="security-stack">
        <div class="card card-pad">
          <p class="card-hint">账号安全为全平台统一，修改后在所有应用生效。</p>
          <div class="readonly-row">
            <span>手机号</span>
            <b v-if="me.maskedPhone" class="rv-value">{{ me.maskedPhone }}</b>
            <span v-else class="rv-badge">未绑定</span>
          </div>
          <div class="readonly-row">
            <span>登录用户名</span>
            <b v-if="me.username" class="rv-value">{{ me.username }}</b>
            <span v-else class="rv-badge">未设置</span>
          </div>
          <div class="readonly-row last">
            <span>邮箱</span>
            <b v-if="me.email" class="rv-value">{{ me.email }}</b>
            <span v-else class="rv-badge">未绑定</span>
          </div>
        </div>

        <div class="card card-pad cred-block">
          <h3>设置登录用户名</h3>
          <input class="input" v-model.trim="usernameInput" type="text" placeholder="2-32 位中文、字母、数字或下划线" />
          <button class="btn btn-primary" :disabled="busy.username" @click="doSetUsername">提交</button>
        </div>

        <div class="card card-pad cred-block">
          <h3>绑定 / 更换邮箱</h3>
          <input class="input" v-model.trim="emailInput" type="email" placeholder="you@example.com" />
          <p v-if="emailInput && !emailValid" class="field-hint-error">邮箱格式不正确，例如 you@example.com</p>
          <input class="input" v-model="emailPassword" type="password" placeholder="当前密码（已设置密码时需校验）" />
          <button class="btn btn-primary" :disabled="busy.email || !emailValid" @click="doBindEmail">提交</button>
        </div>

        <div class="card card-pad cred-block">
          <h3>设置 / 修改密码</h3>
          <p class="card-hint">修改密码后，所有设备将退出登录，需使用新密码重新登录。</p>
          <input class="input" v-model="pwd.oldPassword" type="password" placeholder="原密码（首次设置可留空）" />
          <input class="input" v-model="pwd.newPassword" type="password" placeholder="新密码：至少 8 位，含字母与数字" />
          <div v-if="pwd.newPassword" class="pwd-strength">
            <div class="pwd-strength-bar">
              <div class="pwd-strength-fill" :style="{ width: pwdStrength.pct + '%', background: pwdStrength.color }"></div>
            </div>
            <span class="pwd-strength-label" :style="{ color: pwdStrength.color }">{{ pwdStrength.label }}</span>
          </div>
          <p v-if="pwd.newPassword && !passwordValid" class="field-hint-error">密码至少 8 位，且需同时包含字母和数字</p>
          <button class="btn btn-primary" :disabled="busy.pwd || !passwordValid" @click="doSetPassword">提交</button>
        </div>

        <div class="card card-pad cred-block danger-zone">
          <h3 class="danger-title">危险操作</h3>
          <p class="card-hint">注销 RAGForge 后，账号进入 30 天冷静期，期间登录可恢复；冷静期结束后 RAGForge 数据将被永久删除（不影响你在其它产品的账号）。</p>
          <button class="btn btn-danger" @click="showDeletionStep1 = true">注销账号</button>
        </div>
      </section>

      <!-- 注销确认弹窗 Step 1 -->
      <div v-if="showDeletionStep1" class="modal-overlay" @click.self="showDeletionStep1 = false">
        <div class="modal-box">
          <h3 class="modal-title">确认注销账号</h3>
          <ul class="deletion-info">
            <li>账号将在 <strong>30 天后</strong>永久删除，期间不可登录</li>
            <li>您作为成员加入的组织数据不受影响（知识库归属组织）</li>
            <li>若您是某组织的唯一管理员，请先移交权限后再申请注销</li>
          </ul>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showDeletionStep1 = false">取消</button>
            <button class="btn btn-danger" @click="openDeletionSmsStep">继续，进入短信验证</button>
          </div>
        </div>
      </div>

      <!-- 注销短信验证弹窗 Step 2 -->
      <div v-if="showDeletionStep2" class="modal-overlay" @click.self="showDeletionStep2 = false">
        <div class="modal-box">
          <h3 class="modal-title">短信验证</h3>
          <p class="card-hint">请输入发送至您绑定手机号的验证码（{{ me.maskedPhone || '已绑手机号' }}）</p>
          <div v-if="deletionError" class="tip tip-err">{{ deletionError }}</div>
          <div class="field">
            <label>手机号（确认验证）</label>
            <input class="input" v-model.trim="deletionPhone" type="tel" placeholder="请输入绑定手机号" :disabled="deletionLoading" />
          </div>
          <div class="field">
            <label>短信验证码</label>
            <div class="input-with-suffix">
              <input class="input" style="border:none;border-radius:0;" v-model.trim="deletionSmsCode" type="text" inputmode="numeric" maxlength="6" placeholder="6 位验证码" :disabled="deletionLoading" />
              <button type="button" class="sms-btn" :class="{ disabled: deletionSmsCountdown > 0 || sendingDeletionSms }" :disabled="deletionSmsCountdown > 0 || sendingDeletionSms" @click="sendDeletionSms">{{ deletionSmsBtnLabel }}</button>
            </div>
          </div>
          <div class="field">
            <label>请输入「注销」以确认</label>
            <input class="input" v-model.trim="deletionConfirmText" type="text" placeholder="注销" :disabled="deletionLoading" />
          </div>
          <p class="card-hint" style="color:#b91c1c;">⚠️ 确认后将立即退出登录并返回登录页；30 天内重新登录即可恢复。</p>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showDeletionStep2 = false">取消</button>
            <button class="btn btn-danger" :disabled="deletionLoading || !deletionPhone || !deletionSmsCode || deletionConfirmText !== '注销'" @click="confirmDeletion">确认注销</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProfile, updateProfile, setPassword, bindEmail, setUsername, loadMe, requestAccountDeletion } from '../api/account'
import { sendSmsCode } from '../api/auth'
import { notifyLoggedOut } from '../api/session'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'

const route = useRoute()
const router = useRouter()
const { clearSession } = useAuth()
const toast = useToast()

const tab = ref(route.query.tab === 'security' ? 'security' : 'profile')

const profile = reactive({ displayName: '', avatar: '', bio: '' })
const me = reactive({ username: '', email: '', maskedPhone: '' })
const savingProfile = ref(false)

const pwd = reactive({ oldPassword: '', newPassword: '' })
const usernameInput = ref('')
const emailInput = ref('')
const emailPassword = ref('')
const busy = reactive({ pwd: false, username: false, email: false })

// 前端格式校验
const emailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((emailInput.value || '').trim()))
const passwordValid = computed(() => {
  const s = pwd.newPassword || ''
  return s.length >= 8 && /[A-Za-z]/.test(s) && /\d/.test(s)
})

const pwdStrength = computed(() => {
  const p = pwd.newPassword
  if (!p) return { pct: 0, label: '', color: '' }
  // 与后端改密规则对齐：基线=至少 8 位且同时含字母和数字；未达基线一律"弱"（提交会被拒）。
  const meetsBaseline = p.length >= 8 && /[a-zA-Z]/.test(p) && /[0-9]/.test(p)
  if (!meetsBaseline) return { pct: 25, label: '弱', color: '#dc2626' }
  let bonus = 0
  if (/[A-Z]/.test(p) && /[a-z]/.test(p)) bonus++
  if (/[^A-Za-z0-9]/.test(p)) bonus++
  if (p.length >= 12) bonus++
  if (bonus >= 2) return { pct: 100, label: '强', color: '#059669' }
  return { pct: 60, label: '中', color: '#d97706' }
})

// 账号注销申请
const showDeletionStep1 = ref(false)
const showDeletionStep2 = ref(false)
const deletionPhone = ref('')
const deletionSmsCode = ref('')
const deletionConfirmText = ref('')
const deletionLoading = ref(false)
const deletionError = ref('')
const sendingDeletionSms = ref(false)
const deletionSmsCountdown = ref(0)
let deletionCountdownTimer = null

const deletionSmsBtnLabel = computed(() => {
  if (sendingDeletionSms.value) return '发送中…'
  if (deletionSmsCountdown.value > 0) return `${deletionSmsCountdown.value}s 后重发`
  return '获取验证码'
})

function openDeletionSmsStep() {
  showDeletionStep1.value = false
  showDeletionStep2.value = true
  deletionError.value = ''
  deletionSmsCode.value = ''
}

async function sendDeletionSms() {
  if (sendingDeletionSms.value || deletionSmsCountdown.value > 0) return
  if (!deletionPhone.value) {
    deletionError.value = '请先输入手机号'
    return
  }
  sendingDeletionSms.value = true
  deletionError.value = ''
  try {
    await sendSmsCode({ phone: deletionPhone.value, scene: 'verification' })
    startDeletionCountdown(60)
  } catch (e) {
    deletionError.value = e?.message || '验证码发送失败，请稍后重试'
  } finally {
    sendingDeletionSms.value = false
  }
}

function startDeletionCountdown(seconds) {
  deletionSmsCountdown.value = seconds
  deletionCountdownTimer = setInterval(() => {
    deletionSmsCountdown.value -= 1
    if (deletionSmsCountdown.value <= 0) {
      clearInterval(deletionCountdownTimer)
      deletionCountdownTimer = null
    }
  }, 1000)
}

async function confirmDeletion() {
  if (deletionLoading.value) return
  if (!/^1\d{10}$/.test(deletionPhone.value || '')) {
    deletionError.value = '请输入正确的手机号（11 位大陆手机号）'
    return
  }
  if (!deletionSmsCode.value) {
    deletionError.value = '请输入验证码'
    return
  }
  deletionLoading.value = true
  deletionError.value = ''
  try {
    const body = await requestAccountDeletion({ phone: deletionPhone.value, smsCode: deletionSmsCode.value })
    const data = body?.data ?? body
    showDeletionStep2.value = false
    // 立即登出并跳登录页；用 sessionStorage 传递提示，登录页读取后展示"注销申请已提交"横幅。
    sessionStorage.setItem('rf_deletion_requested', data?.deletionScheduledAt || '1')
    toast.success('注销申请已提交，30 天内重新登录可恢复')
    setTimeout(() => {
      clearSession()
      notifyLoggedOut()
      router.replace('/login')
    }, 1200)
  } catch (e) {
    deletionError.value = e?.message || '注销申请失败，请稍后重试'
  } finally {
    deletionLoading.value = false
  }
}

onUnmounted(() => {
  if (deletionCountdownTimer) clearInterval(deletionCountdownTimer)
})

onMounted(async () => {
  try {
    const body = await getProfile()
    const data = body?.data ?? body
    profile.displayName = data?.displayName || ''
    profile.avatar = data?.avatar || ''
    profile.bio = data?.bio || ''
    me.username = data?.username || ''
    me.email = data?.email || ''
    me.maskedPhone = data?.maskedPhone || ''
    usernameInput.value = me.username
    emailInput.value = me.email
  } catch {
    /* 错误已由拦截器提示 */
  }
})

async function saveProfile() {
  savingProfile.value = true
  try {
    await updateProfile({ displayName: profile.displayName, avatar: profile.avatar, bio: profile.bio })
    await loadMe()
    toast.success('个人资料已保存')
  } catch {
    /* ignore */
  } finally {
    savingProfile.value = false
  }
}

async function doSetPassword() {
  if (!pwd.newPassword) {
    toast.error('请输入新密码')
    return
  }
  if (!passwordValid.value) {
    toast.error('新密码至少 8 位，且需同时包含字母和数字')
    return
  }
  busy.pwd = true
  try {
    await setPassword({ oldPassword: pwd.oldPassword || undefined, newPassword: pwd.newPassword })
    toast.success('密码已更新，请使用新密码重新登录')
    // 改密后网关已撤销该用户全部会话，主动下线并跳登录页，
    // 避免停留在页面上直到下次续期才被动踢出；busy 保持 true 防重复提交
    setTimeout(() => {
      clearSession()
      notifyLoggedOut()
      router.replace('/login')
    }, 1500)
  } catch (e) {
    // authClient 拦截器不自动 toast，凭证操作必须自行提示，否则失败时静默无反馈
    toast.error(e?.message || '密码更新失败')
    busy.pwd = false
  }
}

async function doSetUsername() {
  if (!usernameInput.value) {
    toast.error('请输入用户名')
    return
  }
  busy.username = true
  try {
    await setUsername({ username: usernameInput.value })
    me.username = usernameInput.value
    await loadMe()
    toast.success('用户名已更新')
  } catch (e) {
    toast.error(e?.message || '用户名更新失败')
  } finally {
    busy.username = false
  }
}

async function doBindEmail() {
  if (!emailInput.value) {
    toast.error('请输入邮箱')
    return
  }
  if (!emailValid.value) {
    toast.error('邮箱格式不正确，请输入有效邮箱')
    return
  }
  busy.email = true
  try {
    await bindEmail({ email: emailInput.value, password: emailPassword.value || undefined })
    emailPassword.value = ''
    await loadMe()
    me.email = emailInput.value
    toast.success('邮箱已更新')
  } catch (e) {
    toast.error(e?.message || '邮箱更新失败')
  } finally {
    busy.email = false
  }
}
</script>

<style scoped>
.account-page { max-width: 680px; margin: 0 auto; }

.tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border); margin-bottom: 20px; }
.tab {
  background: none; border: none; padding: 10px 14px; font-size: 14px; color: var(--text-muted);
  cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; font-family: inherit;
  transition: color 0.15s ease;
}
.tab:hover { color: var(--slate); }
.tab:focus-visible { outline: none; }
.tab.active { color: var(--primary); border-bottom-color: var(--primary); font-weight: 600; }

.card-hint { font-size: 13px; color: var(--text-muted); margin: 0 0 18px; line-height: 1.6; }

.field { margin-bottom: 14px; }
.field:last-of-type { margin-bottom: 18px; }
.field label { font-size: 12px; color: var(--gray); display: block; margin-bottom: 6px; font-weight: 600; }
textarea.input { height: auto; min-height: 84px; padding: 9px 12px; line-height: 1.5; resize: vertical; }

.security-stack { display: flex; flex-direction: column; gap: 14px; }

.readonly-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 0; font-size: 14px; border-bottom: 1px solid var(--light);
}
.readonly-row.last { border-bottom: none; padding-bottom: 0; }
.readonly-row > span:first-child { color: var(--text-muted); }
.rv-value { color: var(--navy); font-weight: 600; }
.rv-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  padding: 3px 11px;
  border-radius: var(--radius-full);
  background: #fff7ed;
  color: #b45309;
  border: 1px solid #fed7aa;
}
.rv-badge::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--amber);
}

.cred-block h3 { font-size: 14px; color: var(--navy); margin: 0 0 12px; }
.cred-block .input { margin-bottom: 10px; }
.field-hint-error { margin: -4px 0 10px; font-size: 12px; color: #dc2626; line-height: 1.4; }
.cred-block .btn { margin-top: 2px; }
.danger-zone { border-color: #fecaca; }
.danger-title { font-size: 14px; color: #991b1b; margin: 0 0 12px; }
.pwd-strength { display: flex; align-items: center; gap: 8px; margin: 4px 0 8px; }
.pwd-strength-bar { flex: 1; height: 4px; background: #e2e8f0; border-radius: 2px; overflow: hidden; }
.pwd-strength-fill { height: 100%; border-radius: 2px; transition: width .3s, background .3s; }
.pwd-strength-label { font-size: 11px; font-weight: 600; flex-shrink: 0; }
.modal-overlay {
  position: fixed; inset: 0; background: rgba(15,23,42,.55);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
}
.modal-box {
  background: #fff; border-radius: 14px; padding: 28px 28px 24px; max-width: 440px; width: 90%;
  box-shadow: 0 20px 60px rgba(0,0,0,.18);
}
.modal-title { font-size: 16px; font-weight: 700; color: #0f172a; margin-bottom: 14px; }
.deletion-info { padding-left: 18px; margin-bottom: 18px; }
.deletion-info li { font-size: 13px; color: #475569; margin-bottom: 6px; line-height: 1.5; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 20px; }
.input-with-suffix {
  display: flex; align-items: stretch; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; background: #fff;
}
.sms-btn {
  border: none; border-left: 1px solid #e2e8f0; background: #fff; color: #1d4ed8;
  font-size: 13px; font-weight: 600; padding: 0 14px; cursor: pointer; font-family: inherit; white-space: nowrap;
}
.sms-btn.disabled, .sms-btn:disabled { color: #94a3b8; cursor: not-allowed; }
.tip { padding: 9px 12px; border-radius: 6px; font-size: 13px; margin-bottom: 12px; }
.tip-err { background: #fff1f0; color: #b42318; border: 1px solid #ffccc7; }
</style>
