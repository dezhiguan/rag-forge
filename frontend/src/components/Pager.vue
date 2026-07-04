<template>
  <div class="pager" :class="{ flush }">
    <span class="range">
      第 <b>{{ total ? start + 1 : 0 }}</b>–<b>{{ Math.min(start + size, total) }}</b> 条 / 共 <b>{{ total }}</b> 条
    </span>
    <span class="spacer"></span>
    <span class="psize">
      每页
      <select :value="size" @change="onSize($event)">
        <option v-for="n in sizeOptions" :key="n" :value="n">{{ n }}</option>
      </select>
      {{ unit }}
    </span>
    <span class="pg-btns">
      <button :disabled="page <= 1" @click="go(page - 1)">‹</button>
      <template v-for="(p, i) in pageWindow" :key="i">
        <span v-if="p === '...'" class="ellipsis">…</span>
        <button v-else :class="{ active: p === page }" @click="go(p)">{{ p }}</button>
      </template>
      <button :disabled="page >= totalPages" @click="go(page + 1)">›</button>
    </span>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 10 },
  sizeOptions: { type: Array, default: () => [10, 20, 50] },
  unit: { type: String, default: '条' },
  // flush=true 去掉左右内边距（用于外层已有 padding 的容器，如文档详情整宽 Chunks）
  flush: { type: Boolean, default: false },
})
const emit = defineEmits(['update:page', 'update:size'])

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const start = computed(() => (props.page - 1) * props.size)

// 页码窗口：首页、末页、当前页 ±1，其余用 … 省略
const pageWindow = computed(() => {
  const cur = props.page
  const pages = totalPages.value
  const win = []
  for (let p = 1; p <= pages; p++) {
    if (p === 1 || p === pages || Math.abs(p - cur) <= 1) win.push(p)
  }
  const out = []
  let prev = 0
  for (const p of win) {
    if (p - prev > 1) out.push('...')
    out.push(p)
    prev = p
  }
  return out
})

function go(p) {
  const target = Math.max(1, Math.min(p, totalPages.value))
  if (target !== props.page) emit('update:page', target)
}
function onSize(e) {
  const n = Number(e.target.value)
  if (n !== props.size) emit('update:size', n)
}
</script>

<style scoped>
.pager { display: flex; align-items: center; gap: 10px; padding: 16px 20px 6px; flex-wrap: wrap; }
.pager.flush { padding-left: 0; padding-right: 0; }
.pager .range { font-size: 13px; color: var(--text-muted); }
.pager .range b { color: var(--slate); }
.pager .spacer { flex: 1; }
.pager .psize { font-size: 13px; color: var(--text-muted); display: flex; align-items: center; gap: 6px; }
.pager select { height: 32px; border: 1px solid var(--border); border-radius: 8px; font-size: 13px; color: var(--slate); padding: 0 6px; background: #fff; cursor: pointer; }
.pg-btns { display: flex; gap: 5px; align-items: center; }
.pg-btns button { min-width: 34px; height: 34px; border: 1px solid var(--border); background: #fff; border-radius: 9px; font-size: 13px; color: var(--slate); cursor: pointer; padding: 0 9px; transition: .12s; }
.pg-btns button:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.pg-btns button.active { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.pg-btns button:disabled { opacity: .4; cursor: not-allowed; }
.pg-btns .ellipsis { color: var(--text-muted); padding: 0 2px; user-select: none; }
</style>
