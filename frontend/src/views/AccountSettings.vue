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
          <input class="input" v-model="emailPassword" type="password" placeholder="当前密码（已设置密码时需校验）" />
          <button class="btn btn-primary" :disabled="busy.email" @click="doBindEmail">提交</button>
        </div>

        <div class="card card-pad cred-block">
          <h3>设置 / 修改密码</h3>
          <input class="input" v-model="pwd.oldPassword" type="password" placeholder="原密码（首次设置可留空）" />
          <input class="input" v-model="pwd.newPassword" type="password" placeholder="新密码：至少 8 位，含字母与数字" />
          <button class="btn btn-primary" :disabled="busy.pwd" @click="doSetPassword">提交</button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getProfile, updateProfile, setPassword, bindEmail, setUsername, loadMe } from '../api/account'
import { useToast } from '../composables/useToast'

const route = useRoute()
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
  busy.pwd = true
  try {
    await setPassword({ oldPassword: pwd.oldPassword || undefined, newPassword: pwd.newPassword })
    toast.success('密码已更新')
    pwd.oldPassword = ''
    pwd.newPassword = ''
  } catch (e) {
    // authClient 拦截器不自动 toast，凭证操作必须自行提示，否则失败时静默无反馈
    toast.error(e?.message || '密码更新失败')
  } finally {
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
.cred-block .btn { margin-top: 2px; }
</style>
