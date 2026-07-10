<template>
  <div v-if="doc" class="legal-overlay" @click.self="close">
    <div class="legal-box" role="dialog" aria-modal="true">
      <div class="legal-header">
        <div>
          <h2 class="legal-title">{{ doc.title }}</h2>
          <p class="legal-meta">版本 {{ doc.version }} · 更新于 {{ doc.updatedAt }}</p>
        </div>
        <button class="legal-close" aria-label="关闭" @click="close">×</button>
      </div>
      <div class="legal-body">
        <p v-if="doc.intro" class="legal-intro">{{ doc.intro }}</p>
        <section v-for="(s, i) in doc.sections" :key="i" class="legal-section">
          <h3 class="legal-h">{{ s.h }}</h3>
          <p class="legal-p">{{ s.p }}</p>
        </section>
      </div>
      <div class="legal-footer">
        <button class="btn btn-primary" @click="close">我已阅读</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { legalDocs } from '../data/legalDocs'

// which 可传 'terms' / 'privacy'（从 legalDocs 取），或直接传一个 doc 对象；为空则不渲染。
const props = defineProps({ which: { type: String, default: '' } })
const emit = defineEmits(['close'])

const doc = computed(() => (props.which ? legalDocs[props.which] : null))

function close() {
  emit('close')
}
</script>

<style scoped>
.legal-overlay {
  position: fixed; inset: 0; background: rgba(15,23,42,.7);
  display: flex; align-items: center; justify-content: center; z-index: 10000; padding: 20px;
}
.legal-box {
  background: #fff; border-radius: 16px; width: 100%; max-width: 640px; max-height: 82vh;
  display: flex; flex-direction: column; box-shadow: 0 24px 80px rgba(0,0,0,.25); overflow: hidden;
}
.legal-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  padding: 24px 28px 16px; border-bottom: 1px solid #eef2f7;
}
.legal-title { font-size: 20px; font-weight: 800; color: #0f172a; margin: 0; }
.legal-meta { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.legal-close {
  border: none; background: transparent; font-size: 26px; line-height: 1; color: #94a3b8;
  cursor: pointer; padding: 0 4px; margin: -4px -6px 0 12px;
}
.legal-close:hover { color: #475569; }
.legal-body { padding: 20px 28px; overflow-y: auto; }
.legal-intro { font-size: 13px; color: #475569; line-height: 1.8; margin-bottom: 20px; }
.legal-section { margin-bottom: 18px; }
.legal-h { font-size: 14px; font-weight: 700; color: #1e293b; margin: 0 0 6px; }
.legal-p { font-size: 13px; color: #475569; line-height: 1.85; margin: 0; }
.legal-footer {
  padding: 16px 28px; border-top: 1px solid #eef2f7; display: flex; justify-content: flex-end;
}
.btn { padding: 9px 24px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; border: none; font-family: inherit; }
.btn-primary { background: #1d4ed8; color: #fff; }
.btn-primary:hover { background: #1e40af; }
</style>
