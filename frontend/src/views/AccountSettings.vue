<template>
  <div class="account-page">
    <h1 class="page-title">账号设置</h1>

    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'profile' }" @click="tab = 'profile'">个人设置</button>
      <button class="tab" :class="{ active: tab === 'security' }" @click="tab = 'security'">安全中心</button>
    </div>

    <!-- 个人设置：RAG 本地资料，不调网关 -->
    <section v-if="tab === 'profile'" class="card">
      <p class="card-hint">个人资料仅影响 RAGForge 内的展示，不会同步到其它应用。</p>
      <div class="field">
        <label>显示名</label>
        <input v-model.trim="profile.displayName" type="text" placeholder="用于展示的名称" />
      </div>
      <div class="field">
        <label>头像 URL</label>
        <input v-model.trim="profile.avatar" type="text" placeholder="https://..." />
      </div>
      <div class="field">
        <label>个人简介</label>
        <textarea v-model.trim="profile.bio" rows="3" placeholder="一句话介绍自己"></textarea>
      </div>
      <button class="btn-primary" :disabled="savingProfile" @click="saveProfile">
        {{ savingProfile ? '保存中…' : '保存' }}
      </button>
    </section>

    <!-- 安全中心：凭证统一调网关 -->
    <section v-else class="card">
      <p class="card-hint">账号安全为全平台统一，修改后在所有应用生效。</p>
      <div class="readonly-row"><span>手机号</span><b>{{ me.maskedPhone || '未绑定' }}</b></div>
      <div class="readonly-row"><span>登录用户名</span><b>{{ me.username || '未设置' }}</b></div>
      <div class="readonly-row"><span>邮箱</span><b>{{ me.email || '未绑定' }}</b></div>

      <hr />

      <div class="cred-block">
        <h3>设置 / 修改密码</h3>
        <input v-model="pwd.oldPassword" type="password" placeholder="原密码（首次设置可留空）" />
        <input v-model="pwd.newPassword" type="password" placeholder="新密码：至少 8 位，含字母与数字" />
        <button class="btn-outline" :disabled="busy.pwd" @click="doSetPassword">提交</button>
      </div>

      <div class="cred-block">
        <h3>设置登录用户名</h3>
        <input v-model.trim="usernameInput" type="text" placeholder="3-32 位字母、数字或下划线" />
        <button class="btn-outline" :disabled="busy.username" @click="doSetUsername">提交</button>
      </div>

      <div class="cred-block">
        <h3>绑定 / 更换邮箱</h3>
        <input v-model.trim="emailInput" type="email" placeholder="you@example.com" />
        <input v-model="emailPassword" type="password" placeholder="当前密码（已设置密码时需校验）" />
        <button class="btn-outline" :disabled="busy.email" @click="doBindEmail">提交</button>
      </div>
    </section>
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
  } catch {
    /* ignore */
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
  } catch {
    /* ignore */
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
    me.email = emailInput.value
    emailPassword.value = ''
    toast.success('邮箱已绑定')
  } catch {
    /* ignore */
  } finally {
    busy.email = false
  }
}
</script>

<style scoped>
.account-page { max-width: 640px; }
.page-title { font-size: 20px; font-weight: 700; color: #0f172a; margin: 0 0 16px; }
.tabs { display: flex; gap: 4px; border-bottom: 1px solid #e2e8f0; margin-bottom: 18px; }
.tab {
  background: none; border: none; padding: 10px 16px; font-size: 14px; color: #64748b;
  cursor: pointer; border-bottom: 2px solid transparent; font-family: inherit;
}
.tab.active { color: #1d4ed8; border-bottom-color: #3b82f6; font-weight: 600; }
.card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 20px; }
.card-hint { font-size: 13px; color: #64748b; margin: 0 0 16px; }
.field { margin-bottom: 14px; }
.field label { font-size: 12px; color: #475569; display: block; margin-bottom: 6px; }
.field input, .field textarea, .cred-block input {
  width: 100%; border: 1px solid #e2e8f0; border-radius: 8px; padding: 9px 12px;
  font-size: 14px; color: #1e293b; background: #fff; outline: none; font-family: inherit;
}
.field input { height: 40px; }
.readonly-row {
  display: flex; justify-content: space-between; padding: 10px 0; font-size: 14px; border-bottom: 1px solid #f1f5f9;
}
.readonly-row span { color: #64748b; }
.readonly-row b { color: #0f172a; }
hr { border: none; border-top: 1px solid #e2e8f0; margin: 18px 0; }
.cred-block { margin-bottom: 18px; }
.cred-block h3 { font-size: 14px; color: #0f172a; margin: 0 0 8px; }
.cred-block input { margin-bottom: 8px; }
.btn-primary {
  height: 40px; padding: 0 20px; background: linear-gradient(180deg, #3b82f6, #1d4ed8); color: #fff;
  font-weight: 600; font-size: 14px; border: none; border-radius: 8px; cursor: pointer; font-family: inherit;
}
.btn-primary:disabled { background: #93c5fd; cursor: not-allowed; }
.btn-outline {
  height: 38px; padding: 0 18px; background: #fff; color: #1d4ed8; font-weight: 600; font-size: 13px;
  border: 1px solid #bfdbfe; border-radius: 8px; cursor: pointer; font-family: inherit;
}
.btn-outline:disabled { color: #94a3b8; border-color: #e2e8f0; cursor: not-allowed; }
</style>
