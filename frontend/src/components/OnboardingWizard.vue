<template>
  <div v-if="visible" class="onboarding-overlay">
    <div class="onboarding-box">
      <!-- Step 1: Choose use case -->
      <div v-if="step === 1">
        <h2 class="ob-title">欢迎使用 RAGForge 🎉</h2>
        <p class="ob-desc">请选择您的主要使用场景，帮助我们为您个性化引导：</p>
        <div class="ob-options">
          <button
            v-for="opt in useCases"
            :key="opt.key"
            class="ob-option"
            :class="{ selected: selectedUseCase === opt.key }"
            @click="selectedUseCase = opt.key"
          >
            <span class="ob-option-icon">{{ opt.icon }}</span>
            <span class="ob-option-label">{{ opt.label }}</span>
          </button>
        </div>
        <div class="ob-actions">
          <button class="ob-skip" @click="skip">跳过引导</button>
          <button class="btn btn-primary" :disabled="!selectedUseCase" @click="step = 2">下一步</button>
        </div>
      </div>

      <!-- Step 2: Create first knowledge base -->
      <div v-else-if="step === 2">
        <h2 class="ob-title">创建您的第一个知识库</h2>
        <p class="ob-desc">知识库是您上传和管理文档的地方。给它起个名字吧！</p>
        <div v-if="createError" class="tip tip-err">{{ createError }}</div>
        <div class="field">
          <label>知识库名称</label>
          <input
            class="input"
            v-model.trim="kbName"
            type="text"
            placeholder="例如：产品手册、研究报告…"
            :disabled="creating"
            maxlength="60"
          />
        </div>
        <div class="field">
          <label>描述（选填）</label>
          <input
            class="input"
            v-model.trim="kbDesc"
            type="text"
            placeholder="简单描述一下这个知识库的用途"
            :disabled="creating"
            maxlength="200"
          />
        </div>
        <div class="ob-actions">
          <button class="ob-skip" @click="skip">跳过</button>
          <button class="btn btn-primary" :disabled="!kbName || creating" @click="createKbAndComplete">
            {{ creating ? '创建中…' : '创建并进入' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { completeOnboarding } from '../api/account'
import { createKb } from '../api/kb'

defineProps({ visible: Boolean })
const emit = defineEmits(['done'])

const router = useRouter()
const step = ref(1)
const selectedUseCase = ref('')
const kbName = ref('')
const kbDesc = ref('')
const creating = ref(false)
const createError = ref('')

const useCases = [
  { key: 'personal', icon: '📚', label: '个人知识管理' },
  { key: 'team', icon: '👥', label: '团队协作' },
  { key: 'api', icon: '🔌', label: '为应用提供 RAG 服务' },
]

async function createKbAndComplete() {
  if (!kbName.value || creating.value) return
  creating.value = true
  createError.value = ''
  try {
    const result = await createKb({ name: kbName.value, description: kbDesc.value })
    try { await completeOnboarding() } catch {}
    const kb = result?.data ?? result
    emit('done')
    if (kb?.id) {
      router.push(`/knowledge/${kb.id}/documents`)
    } else {
      router.push('/knowledge')
    }
  } catch (e) {
    createError.value = e?.message || '创建失败，请稍后重试'
  } finally {
    creating.value = false
  }
}

async function skip() {
  try { await completeOnboarding() } catch {}
  emit('done')
}
</script>

<style scoped>
.onboarding-overlay {
  position: fixed; inset: 0; background: rgba(15,23,42,.7);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.onboarding-box {
  background: #fff; border-radius: 16px; padding: 40px 32px; max-width: 480px; width: 90%;
  box-shadow: 0 24px 80px rgba(0,0,0,.2);
}
.ob-title { font-size: 20px; font-weight: 800; color: #0f172a; margin-bottom: 10px; }
.ob-desc { font-size: 14px; color: #475569; margin-bottom: 20px; line-height: 1.6; }
.ob-options { display: flex; flex-direction: column; gap: 10px; margin-bottom: 24px; }
.ob-option {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
  border: 2px solid #e2e8f0; border-radius: 10px; background: #fff;
  cursor: pointer; font-size: 14px; font-family: inherit; text-align: left; transition: all .15s;
}
.ob-option:hover { border-color: #93c5fd; background: #eff6ff; }
.ob-option.selected { border-color: #1d4ed8; background: #eff6ff; }
.ob-option-icon { font-size: 20px; }
.ob-option-label { color: #1e293b; font-weight: 600; }
.ob-actions { display: flex; align-items: center; justify-content: space-between; }
.ob-skip { background: none; border: none; color: #94a3b8; font-size: 13px; cursor: pointer; padding: 0; font-family: inherit; }
.ob-skip:hover { color: #475569; }
.field { margin-bottom: 14px; }
.field label { font-size: 12px; color: #475569; display: block; margin-bottom: 6px; font-weight: 600; }
.input {
  width: 100%; height: 40px; border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 0 12px; font-size: 14px; color: #1e293b; outline: none; font-family: inherit;
}
.input:focus { border-color: #1d4ed8; box-shadow: 0 0 0 3px rgba(29,78,216,.12); }
.btn { padding: 9px 24px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; border: none; font-family: inherit; }
.btn-primary { background: #1d4ed8; color: #fff; }
.btn-primary:disabled { opacity: .6; cursor: not-allowed; }
.tip { padding: 9px 12px; border-radius: 6px; font-size: 13px; margin-bottom: 12px; }
.tip-err { background: #fff1f0; color: #b42318; border: 1px solid #ffccc7; }
</style>
