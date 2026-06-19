<template>
  <div class="dialog-mask" role="presentation" @click.self="$emit('cancel')">
    <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="logout-all-title">
      <h2 id="logout-all-title">退出所有设备</h2>
      <p>此操作会吊销 RAGForge 与 CareerMate 全端 session。请输入当前账号密码确认。</p>
      <label for="logoutAllPassword">当前密码</label>
      <input
        id="logoutAllPassword"
        v-model="password"
        type="password"
        autocomplete="current-password"
        placeholder="请输入当前密码"
        @keydown.enter.prevent="submit"
      />
      <div class="dialog-actions">
        <button type="button" class="btn ghost" @click="$emit('cancel')">取消</button>
        <button type="button" class="btn danger" :disabled="!password || loading" @click="submit">
          {{ loading ? '退出中...' : '确认退出所有设备' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['cancel', 'confirm'])
const password = ref('')

function submit() {
  if (!password.value || props.loading) return
  emit('confirm', password.value)
}
</script>

<style scoped>
.dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 300;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(15, 23, 42, 0.48);
}

.dialog {
  width: min(420px, 100%);
  border-radius: 8px;
  background: #fff;
  padding: 22px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.28);
}

h2 {
  margin: 0;
  color: #0f172a;
  font-size: 20px;
}

p {
  margin: 8px 0 18px;
  color: #475569;
  font-size: 14px;
  line-height: 1.7;
}

label {
  display: block;
  margin-bottom: 6px;
  color: #475569;
  font-size: 12px;
}

input {
  width: 100%;
  height: 40px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 0 12px;
  font-size: 14px;
  outline: none;
}

input:focus {
  border-color: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.12);
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}

.btn {
  height: 36px;
  border-radius: 8px;
  padding: 0 14px;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #334155;
  font-weight: 600;
  cursor: pointer;
}

.btn.danger {
  border-color: #ef4444;
  background: #ef4444;
  color: #fff;
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
