<template>
  <div class="cancelling-wrap">
    <div class="cancelling-card">
      <div class="cc-icon">!</div>
      <h2 class="cc-title">账号注销中</h2>
      <p class="cc-desc">你的账号已申请注销，将于</p>
      <p class="cc-desc"><span class="cc-big">{{ daysLeft }} 天后</span>（{{ dateText }}）永久删除</p>
      <p class="cc-note">届时你的组织成员身份、API Key 等 RAGForge 数据将被清除；知识库归属组织不受影响。恢复账号即可继续使用。</p>
      <div v-if="errorMsg" class="tip tip-err">{{ errorMsg }}</div>
      <button class="btn btn-primary" :disabled="busy" @click="restore">{{ busy ? '恢复中…' : '恢复账号，继续使用' }}</button>
      <button class="btn btn-ghost" :disabled="busy" @click="leave">确认离开</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchDeletionStatus, cancelAccountDeletion } from '../../api/account'
import { logout as logoutApi } from '../../api/auth'
import { notifyLoggedOut } from '../../api/session'
import { useAuth } from '../../composables/useAuth'
import { useToast } from '../../composables/useToast'

const router = useRouter()
const toast = useToast()
const { clearSession } = useAuth()
const busy = ref(false)
const errorMsg = ref('')
const scheduledAt = ref(null)

const daysLeft = computed(() => {
  if (!scheduledAt.value) return 30
  const ms = new Date(scheduledAt.value).getTime() - Date.now()
  return Math.max(0, Math.ceil(ms / 86400000))
})
const dateText = computed(() => {
  if (!scheduledAt.value) return ''
  const d = new Date(scheduledAt.value)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

onMounted(async () => {
  try {
    const body = await fetchDeletionStatus()
    const data = body?.data ?? body
    if (!data?.pendingDeletion) { router.replace('/'); return } // 已恢复/未注销 → 回主界面
    scheduledAt.value = data?.deletionScheduledAt || null
  } catch { /* 拦截器已提示；留在本页 */ }
})

async function restore() {
  if (busy.value) return
  busy.value = true
  errorMsg.value = ''
  try {
    await cancelAccountDeletion()
    toast.success('已恢复账号，欢迎回来')
    router.replace('/')
  } catch (e) {
    errorMsg.value = e?.message || '恢复失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

async function leave() {
  if (busy.value) return
  busy.value = true
  try { await logoutApi() } catch { /* 忽略登出网络错误 */ }
  clearSession()
  notifyLoggedOut()
  router.replace('/login')
}
</script>

<style scoped>
.cancelling-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f1f5f9; padding: 20px; }
.cancelling-card { background: #fff; border-radius: 16px; box-shadow: 0 8px 32px rgba(15,23,42,.12); padding: 40px 32px; max-width: 420px; width: 100%; text-align: center; }
.cc-icon { width: 56px; height: 56px; border-radius: 50%; background: #fff7ed; color: #ea580c; font-size: 30px; font-weight: 700; display: flex; align-items: center; justify-content: center; margin: 0 auto 16px; }
.cc-title { font-size: 20px; font-weight: 800; color: #0f172a; margin: 0 0 12px; }
.cc-desc { font-size: 14px; color: #475569; margin: 2px 0; }
.cc-big { font-size: 18px; font-weight: 800; color: #dc2626; }
.cc-note { font-size: 12.5px; color: #94a3b8; line-height: 1.7; margin: 12px 0 20px; }
.btn { width: 100%; border: 0; border-radius: 10px; padding: 12px; font-size: 14px; font-weight: 700; cursor: pointer; font-family: inherit; margin-top: 10px; }
.btn-primary { background: #1d4ed8; color: #fff; }
.btn-primary:disabled { opacity: .6; cursor: not-allowed; }
.btn-ghost { background: #fff; color: #64748b; border: 1px solid #e2e8f0; }
.btn-ghost:disabled { opacity: .6; }
.tip { padding: 9px 12px; border-radius: 8px; font-size: 13px; margin-bottom: 8px; }
.tip-err { background: #fff1f0; color: #b42318; border: 1px solid #ffccc7; }
</style>
