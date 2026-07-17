<template>
  <div class="pt-wrap">
    <div v-if="!rows || rows.length === 0" class="pt-empty">
      该工具无需入参，arguments 传空对象 <code>{}</code>。
    </div>
    <table v-else class="pt">
      <thead>
        <tr><th>字段</th><th>类型</th><th>必填</th><th>说明</th></tr>
      </thead>
      <tbody>
        <tr v-for="p in rows" :key="p.n">
          <td><span class="pt-name">{{ p.n }}</span></td>
          <td><span class="ty" :class="'ty-' + p.t">{{ p.t }}</span></td>
          <td><span v-if="p.r" class="rq">必填</span><span v-else class="op">可选</span></td>
          <td class="pt-desc">
            {{ p.d }}
            <template v-if="p.enums"><code v-for="e in p.enums" :key="e" class="enum">{{ e }}</code></template>
            <span v-if="p.def" class="pt-def">默认 {{ p.def }}</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
defineProps({
  rows: { type: Array, default: () => [] },
})
</script>

<style scoped>
.pt-wrap { overflow-x: auto; }
.pt-empty { font-size: 13px; color: var(--text-muted); padding: 10px 2px; }
.pt-empty code { font-family: ui-monospace, Menlo, monospace; background: var(--primary-soft); border-radius: 4px; padding: 1px 5px; color: var(--navy); }
.pt { width: 100%; border-collapse: collapse; font-size: 13px; }
.pt th { text-align: left; font-size: 11px; font-weight: 700; color: var(--text-muted); padding: 0 12px 9px; border-bottom: 1px solid var(--border); white-space: nowrap; }
.pt td { padding: 11px 12px; border-bottom: 1px solid var(--border); vertical-align: top; }
.pt tbody tr:last-child td { border-bottom: 0; }
.pt-name { font-family: ui-monospace, Menlo, monospace; font-weight: 700; color: var(--navy); font-size: 12.5px; }
.ty { font-family: ui-monospace, Menlo, monospace; font-size: 11px; font-weight: 700; padding: 2px 7px; border-radius: 6px; white-space: nowrap; }
.ty-string { color: #0369a1; background: #e0f2fe; }
.ty-number { color: #7c3aed; background: #f3e8ff; }
.ty-boolean { color: #0d9488; background: #ccfbf1; }
.ty-array { color: #c2410c; background: #ffedd5; }
.ty-object { color: #475569; background: #e2e8f0; }
.rq { font-size: 11px; font-weight: 700; color: #dc2626; }
.op { font-size: 11px; color: var(--text-muted); }
.pt-desc { color: var(--gray); line-height: 1.6; }
.enum { font-family: ui-monospace, Menlo, monospace; font-size: 11.5px; background: var(--surface); border: 1px solid var(--border); border-radius: 4px; padding: 1px 5px; margin: 0 2px; color: var(--navy); }
.pt-def { color: var(--text-muted); font-size: 12px; margin-left: 4px; }
</style>
