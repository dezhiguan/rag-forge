<template>
  <div v-if="visible" class="terms-modal-overlay">
    <div class="terms-modal-box">
      <h2 class="terms-modal-title">协议更新提醒</h2>
      <p class="terms-modal-desc">
        RAGForge 的《用户协议》和《隐私政策》已更新。继续使用前请阅读并同意最新协议。
      </p>
      <div class="terms-modal-links">
        <a href="#" class="terms-link" @click.prevent="legalDoc = 'terms'">查看用户协议</a>
        <a href="#" class="terms-link" @click.prevent="legalDoc = 'privacy'">查看隐私政策</a>
      </div>
      <LegalDocModal :which="legalDoc" @close="legalDoc = ''" />
      <div v-if="error" class="tip tip-err">{{ error }}</div>
      <div class="terms-modal-actions">
        <button class="btn btn-secondary" :disabled="loading" @click="decline">拒绝（退出登录）</button>
        <button class="btn btn-primary" :disabled="loading" @click="accept">同意并继续</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { acceptTerms } from '../api/account'
import { logout } from '../api/auth'
import { useAuth } from '../composables/useAuth'
import { notifyLoggedOut } from '../api/session'
import LegalDocModal from './LegalDocModal.vue'

defineProps({ visible: Boolean })
const emit = defineEmits(['accepted'])

const router = useRouter()
const { clearSession } = useAuth()
const loading = ref(false)
const error = ref('')
const legalDoc = ref('')

async function accept() {
  loading.value = true
  error.value = ''
  try {
    await acceptTerms({ termsVersion: '1.0' })
    emit('accepted')
  } catch (e) {
    error.value = e?.message || '同意协议失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function decline() {
  loading.value = true
  try {
    await logout()
  } catch {}
  clearSession()
  notifyLoggedOut()
  router.replace('/login')
}
</script>

<style scoped>
.terms-modal-overlay {
  position: fixed; inset: 0; background: rgba(15,23,42,.7);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.terms-modal-box {
  background: #fff; border-radius: 16px; padding: 40px 32px; max-width: 480px; width: 90%;
  box-shadow: 0 24px 80px rgba(0,0,0,.2);
}
.terms-modal-title { font-size: 20px; font-weight: 800; color: #0f172a; margin-bottom: 12px; }
.terms-modal-desc { font-size: 14px; color: #475569; line-height: 1.7; margin-bottom: 16px; }
.terms-modal-links { display: flex; gap: 16px; margin-bottom: 20px; }
.terms-link { color: #1d4ed8; font-size: 13px; font-weight: 600; text-decoration: none; }
.terms-link:hover { text-decoration: underline; }
.terms-modal-actions { display: flex; gap: 12px; justify-content: flex-end; margin-top: 20px; }
.btn { padding: 9px 20px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; border: none; font-family: inherit; }
.btn-primary { background: #1d4ed8; color: #fff; }
.btn-primary:disabled { opacity: .6; cursor: not-allowed; }
.btn-secondary { background: #fff; border: 1px solid #e2e8f0; color: #334155; }
.btn-secondary:disabled { opacity: .6; cursor: not-allowed; }
.tip { padding: 9px 12px; border-radius: 6px; font-size: 13px; margin-bottom: 12px; }
.tip-err { background: #fff1f0; color: #b42318; border: 1px solid #ffccc7; }
</style>
