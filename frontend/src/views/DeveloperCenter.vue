<template>
  <div class="page-body dev-center">
    <div class="page-s">管理接入凭证、查阅接口与 MCP 集成方式。凭证与接口示例均归属<b>当前组织</b>，切换组织即切换上下文。</div>

    <!-- 子 tab -->
    <div class="seg">
      <button :class="{ on: tab === 'keys' }" @click="tab = 'keys'">🔑 API keys</button>
      <button :class="{ on: tab === 'api' }" @click="tab = 'api'">🔗 接口文档</button>
      <button :class="{ on: tab === 'mcp' }" @click="tab = 'mcp'">🧠 MCP 接入</button>
    </div>

    <!-- scope 提示 -->
    <div class="scope-note" :class="{ gov: isPlatform }">
      <template v-if="isPlatform">
        🛡 <span><b>全平台治理视图（破玻璃）</b> —— 跨组织只读查看所有 API key，仅可<b>吊销</b>疑似泄露/违规的 key；无法替组织创建或编辑。操作记审计。</span>
      </template>
      <template v-else>
        🛡 <span>当前展示 <b>{{ orgName }}</b> 的接入凭证与接口。密钥仅在本组织内有效；调用自动绑定 <b>X-Org-Id={{ orgIdText }}</b>，只能访问本组织（及公开）知识库。</span>
      </template>
    </div>

    <!-- ============ API keys ============ -->
    <div v-show="tab === 'keys'" class="card card-pad">
      <div v-if="newKey" class="newkey">
        <div class="nk-h">🔑 API key 创建成功 —— 明文<b>仅显示这一次</b>，请立即复制并妥善保管</div>
        <div class="nk-row"><code>{{ newKey }}</code><button class="btn" @click="copy($event, newKey)">复制</button></div>
        <div class="nk-tip">⚠️ 离开本页或刷新后将<b>无法再次查看</b>完整 key；如遗失只能删除后重建。请勿暴露在前端代码或公开仓库。</div>
      </div>

      <div class="desc">
        <template v-if="isPlatform">下表是<b>全平台所有组织</b>的 API key（只读）。发现疑似泄露可一键吊销；不能在此为某组织新建 key，请下钻到对应组织。</template>
        <template v-else>下表是<b>本组织</b>的全部 API key。Key 仅在创建时可见可复制，请妥善保存，不要暴露在前端代码中。</template>
      </div>

      <table>
        <thead v-if="!isPlatform"><tr><th>名称</th><th>Key</th><th>状态</th><th>创建日期</th><th>最近使用</th><th></th></tr></thead>
        <thead v-else><tr><th>名称</th><th>所属组织</th><th>Key</th><th>最近使用</th><th>状态</th><th></th></tr></thead>
        <tbody>
          <tr v-if="!keys.length"><td :colspan="6" class="empty">{{ loading ? '加载中…' : '暂无 API key' }}</td></tr>
          <!-- 组织态 -->
          <template v-if="!isPlatform">
            <tr v-for="k in keys" :key="k.id">
              <td class="kname">{{ k.keyName }}</td>
              <td class="kcode">{{ k.keyMasked }}</td>
              <td><span class="tag" :class="k.enabled ? 't-on' : 't-off'">{{ k.enabled ? '启用' : '已停用' }}</span></td>
              <td>{{ fmt(k.createdAt) }}</td>
              <td>{{ fmt(k.lastUsedAt) || '—' }}</td>
              <td><div class="row-act" v-if="canManage">
                <span @click="onToggle(k)">{{ k.enabled ? '停用' : '启用' }}</span>
                <span class="del" @click="onDelete(k)">删除</span>
              </div><span v-else class="muted">只读</span></td>
            </tr>
          </template>
          <!-- 治理态 -->
          <template v-else>
            <tr v-for="k in keys" :key="k.id">
              <td class="kname">{{ k.keyName }}</td>
              <td>{{ k.orgName || ('org#' + k.orgId) }}</td>
              <td class="kcode">{{ k.keyMasked }}</td>
              <td>{{ fmt(k.lastUsedAt) || '—' }}</td>
              <td><span class="tag" :class="k.enabled ? 't-on' : 't-off'">{{ k.enabled ? '正常' : '已吊销' }}</span></td>
              <td><div class="row-act">
                <span v-if="k.enabled" class="del" @click="onRevoke(k)">吊销</span>
                <span v-else class="muted">—</span>
              </div></td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-if="canManage" class="mt16"><button class="btn-primary" :disabled="creating" @click="onCreate">＋ 创建 API key</button></div>
    </div>

    <!-- ============ 接口文档 ============ -->
    <div v-show="tab === 'api'">
      <div class="grid2">
        <div class="card card-pad">
          <div class="sec-title">⚙️ 接入信息</div>
          <div class="sec-hint">把以下信息配置到你的客户端 / 服务端。</div>
          <div class="kv"><span class="k">Base URL</span><span class="v">{{ baseUrl }}<span class="copy" @click="copy($event, baseUrl)">复制</span></span></div>
          <div class="kv"><span class="k">认证方式</span><span class="v">Authorization: Bearer &lt;API key&gt;</span></div>
          <div class="kv"><span class="k">组织上下文</span><span class="v">X-Org-Id: {{ orgIdText }}（随密钥绑定）</span></div>
        </div>
        <div class="card card-pad">
          <div class="sec-title">🔗 核心接口</div>
          <div class="sec-hint">均按当前组织上下文过滤数据。</div>
          <div class="ep"><span class="m m-post">POST</span><span class="ep-path">/search</span><span class="ep-desc">混合检索</span></div>
          <div class="ep"><span class="m m-get">GET</span><span class="ep-path">/kb</span><span class="ep-desc">本组织知识库列表</span></div>
          <div class="ep"><span class="m m-post">POST</span><span class="ep-path">/kb/&#123;id&#125;/documents</span><span class="ep-desc">上传文档入库</span></div>
          <div class="ep"><span class="m m-get">GET</span><span class="ep-path">/kb/&#123;id&#125;</span><span class="ep-desc">知识库详情</span></div>
        </div>
      </div>
      <div class="card card-pad mt16">
        <div class="sec-title">📋 检索调用示例（cURL）</div>
        <pre><button class="pre-copy" @click="copy($event, curlText)">复制</button>{{ curlText }}</pre>
      </div>
    </div>

    <!-- ============ MCP 接入 ============ -->
    <div v-show="tab === 'mcp'">
      <div class="grid2">
        <div class="card card-pad">
          <div class="sec-title">⚙️ MCP Server 信息</div>
          <div class="sec-hint">把 RAGForge 作为 MCP Server 接入支持 MCP 的客户端。</div>
          <div class="kv"><span class="k">协议</span><span class="v">MCP (streamable HTTP)</span></div>
          <div class="kv"><span class="k">Server URL</span><span class="v">{{ mcpUrl }}<span class="copy" @click="copy($event, mcpUrl)">复制</span></span></div>
          <div class="kv"><span class="k">认证</span><span class="v">Authorization: Bearer &lt;API key&gt;</span></div>
          <div class="kv"><span class="k">组织上下文</span><span class="v">X-Org-Id: {{ orgIdText }}</span></div>
        </div>
        <div class="card card-pad">
          <div class="sec-title">🤝 适用客户端</div>
          <div class="sec-hint">凡支持 MCP 的客户端均可接入。</div>
          <div class="ep"><span class="m m-post">Claude</span><span class="ep-path">Claude Desktop / Code</span></div>
          <div class="ep"><span class="m m-get">Agent</span><span class="ep-path">CareerMate</span></div>
          <div class="ep"><span class="m m-get">SDK</span><span class="ep-path">任意 MCP Client</span></div>
        </div>
      </div>
      <div class="card card-pad mt16">
        <div class="sec-title">🧠 MCP 配置</div>
        <div class="sec-hint">把以下配置加入客户端。Server 暴露的工具仅作用于<b>当前组织</b>。</div>
        <pre><button class="pre-copy" @click="copy($event, mcpText)">复制</button>{{ mcpText }}</pre>
        <div class="sec-title" style="margin:24px 0 6px;">可用 MCP 工具</div>
        <div class="mcp-tool" v-for="t in mcpTools" :key="t.name">
          <span class="mcp-ico">{{ t.ico }}</span><span class="mcp-name">{{ t.name }}</span><span class="mcp-d">{{ t.desc }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useOrg } from '../composables/useOrg'
import { useToast } from '../composables/useToast'
import { confirm as confirmDialog } from '../composables/useConfirm'
import { listApiKeys, createApiKey, enableApiKey, deleteApiKey } from '../api/apikey'

const { current, isPlatform, currentOrgId } = useOrg()
const toast = useToast()

const tab = ref('keys')
const keys = ref([])
const loading = ref(false)
const creating = ref(false)
const newKey = ref('')

// 仅当前组织 OWNER/ADMIN 可管理 key（员工只读，与后端一致）。
const canManage = computed(() => {
  const r = current.value?.myRole
  return !isPlatform.value && (r === 'OWNER' || r === 'ADMIN')
})
const orgName = computed(() => current.value?.name || '当前组织')
const orgIdText = computed(() => (isPlatform.value ? '—' : (currentOrgId.value ?? '—')))
const baseUrl = computed(() => `${location.origin}/api/v1`)
const mcpUrl = computed(() => `${location.origin}/mcp`)
const curlText = computed(
  () =>
    `curl ${baseUrl.value}/search \\\n` +
    `  -H "Authorization: Bearer <API key>" \\\n` +
    `  -H "X-Org-Id: ${orgIdText.value}" \\\n` +
    `  -H "Content-Type: application/json" \\\n` +
    `  -d '{ "query": "Java 高并发经验", "strategy": "hybrid", "topK": 5 }'`,
)
const mcpText = computed(
  () =>
    `{\n  "mcpServers": {\n    "ragforge": {\n      "url": "${mcpUrl.value}",\n` +
    `      "headers": {\n        "Authorization": "Bearer <API key>",\n        "X-Org-Id": "${orgIdText.value}"\n      }\n    }\n  }\n}`,
)
const mcpTools = [
  { ico: '🔍', name: 'search_knowledge', desc: '在本组织知识库内混合检索，返回带引用的片段' },
  { ico: '📚', name: 'list_knowledge_bases', desc: '列出本组织可访问的知识库' },
  { ico: '📄', name: 'get_document', desc: '按 id 读取文档原文/分块' },
  { ico: '➕', name: 'ingest_document', desc: '上传文档进入本组织知识库（需写权限密钥）' },
]

function fmt(s) {
  return s ? String(s).slice(0, 10) : ''
}

async function reload() {
  loading.value = true
  newKey.value = ''
  try {
    const res = await listApiKeys()
    keys.value = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
  } catch (e) {
    /* 全局拦截提示 */
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (creating.value) return
  const name = await confirmDialog({
    title: '创建 API key',
    message:
      '为该 key 取一个便于识别的名称（如：招聘 Agent 接入）。\n\n⚠️ 创建后完整明文 key 仅显示这一次，请立即复制并妥善保管；遗失只能删除重建。',
    input: true,
    inputPlaceholder: 'key 名称',
    confirmText: '创建',
  })
  if (!name) return
  creating.value = true
  try {
    const res = await createApiKey(String(name).trim())
    const created = res?.data ?? res
    newKey.value = created?.apiKey || ''
    await reload()
    toast.success('API key 已创建')
  } catch (e) {
    /* 全局拦截提示 */
  } finally {
    creating.value = false
  }
}

async function onToggle(k) {
  await enableApiKey(k.id, !k.enabled)
  await reload()
  toast.success(k.enabled ? '已停用' : '已启用')
}

async function onRevoke(k) {
  const ok = await confirmDialog({
    title: '吊销 API key',
    message: `确认吊销「${k.keyName}」（${k.orgName || ''}）？吊销后该 key 立即失效。`,
    confirmText: '吊销',
  })
  if (!ok) return
  await enableApiKey(k.id, false)
  await reload()
  toast.success('已吊销')
}

async function onDelete(k) {
  const ok = await confirmDialog({
    title: '删除 API key',
    message: `确认删除「${k.keyName}」？删除后不可恢复。`,
    confirmText: '删除',
  })
  if (!ok) return
  await deleteApiKey(k.id)
  await reload()
  toast.success('已删除')
}

function copy(e, txt) {
  navigator.clipboard?.writeText(txt)
  const el = e.target
  const t = el.textContent
  el.textContent = '已复制'
  setTimeout(() => (el.textContent = t), 1200)
}

// 切组织 → 重新拉取该组织的 key
watch(currentOrgId, () => reload())
onMounted(reload)
</script>

<style scoped>
.dev-center { padding: 20px 28px 48px; }
.page-s { font-size: 12.5px; color: var(--text-muted); max-width: 720px; margin: 0 0 16px; }

.seg { display: inline-flex; background: #eef2f7; border-radius: 11px; padding: 3px; margin-bottom: 16px; }
.seg button { border: 0; background: transparent; color: var(--gray); font-size: 13.5px; font-weight: 600; padding: 7px 18px; border-radius: 9px; cursor: pointer; }
.seg button.on { background: #fff; color: var(--navy); box-shadow: var(--shadow-sm); }

.scope-note { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--gray); background: var(--primary-soft); border: 1px solid var(--primary-border); border-radius: 12px; padding: 10px 14px; margin-bottom: 16px; }
.scope-note b { color: var(--navy); }
.scope-note.gov { background: #fff5f5; border-color: #fecaca; }

.card { background: var(--surface); border: 1px solid var(--border); border-radius: 16px; box-shadow: var(--shadow-sm); }
.card-pad { padding: 20px 22px; }
.desc { font-size: 13px; color: var(--gray); line-height: 1.7; margin: 2px 0 16px; max-width: 760px; }

table { width: 100%; border-collapse: collapse; }
thead th { text-align: left; font-size: 12px; font-weight: 700; color: var(--text-muted); padding: 0 14px 12px; border-bottom: 1px solid var(--border); }
tbody td { padding: 15px 14px; border-bottom: 1px solid var(--border); font-size: 13px; vertical-align: middle; }
tbody tr:last-child td { border-bottom: 0; }
.empty { text-align: center; color: var(--text-muted); padding: 28px 0; }
.kname { font-weight: 700; color: var(--navy); }
.kcode { font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; color: var(--slate); }
.tag { display: inline-block; font-size: 11px; font-weight: 700; padding: 2px 9px; border-radius: 999px; }
.t-on { background: #e8f6ee; color: #15803d; } .t-off { background: #fee2e2; color: #dc2626; }
.row-act { display: flex; gap: 14px; } .row-act span { cursor: pointer; color: var(--primary); font-weight: 600; }
.row-act .del { color: var(--red, #dc2626); } .row-act .muted { color: var(--text-muted); cursor: default; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 16px; border: none; border-radius: 9px; background: var(--primary); color: #fff; font-size: 13px; font-weight: 700; cursor: pointer; }
.btn-primary:disabled { opacity: .6; cursor: not-allowed; }
.btn { height: 30px; padding: 0 12px; border: 1px solid var(--border); background: #fff; border-radius: 8px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
.mt16 { margin-top: 16px; }

.newkey { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 12px; padding: 12px 14px; margin-bottom: 16px; }
.nk-h { font-size: 12.5px; font-weight: 700; color: #15803d; margin-bottom: 8px; }
.nk-row { display: flex; align-items: center; gap: 10px; }
.nk-row code { flex: 1; font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; color: var(--navy); word-break: break-all; }
.nk-tip { margin-top: 8px; font-size: 11.5px; color: #b45309; line-height: 1.6; } .nk-tip b { color: #b45309; }

.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.sec-title { font-size: 13px; font-weight: 700; color: var(--navy); margin: 0 0 4px; }
.sec-hint { font-size: 12px; color: var(--text-muted); margin-bottom: 12px; }
.kv { display: flex; justify-content: space-between; gap: 12px; padding: 11px 0; border-bottom: 1px dashed var(--border); font-size: 13px; }
.kv:last-child { border-bottom: 0; } .kv .k { color: var(--gray); } .kv .v { font-family: ui-monospace, Menlo, monospace; color: var(--navy); font-weight: 600; }
.copy { font-size: 11px; color: var(--primary); font-weight: 700; cursor: pointer; margin-left: 8px; }
.ep { display: flex; align-items: center; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--border); }
.ep:last-child { border-bottom: 0; }
.m { font-size: 11px; font-weight: 800; padding: 3px 8px; border-radius: 7px; min-width: 48px; text-align: center; }
.m-get { background: #e8f6ee; color: #15803d; } .m-post { background: var(--primary-soft); color: var(--primary); }
.ep-path { font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; color: var(--navy); font-weight: 600; }
.ep-desc { font-size: 12px; color: var(--text-muted); margin-left: auto; }
pre { margin: 0; background: #0f1726; color: #d7e2f4; border-radius: 12px; padding: 16px 18px; font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; line-height: 1.7; overflow: auto; position: relative; white-space: pre; }
.pre-copy { position: absolute; top: 10px; right: 12px; background: #1f2a3d; color: #9cc2ff; border: 0; border-radius: 7px; padding: 5px 10px; font-size: 11px; font-weight: 700; cursor: pointer; }
.mcp-tool { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.mcp-tool:last-child { border-bottom: 0; }
.mcp-ico { width: 30px; height: 30px; border-radius: 9px; background: #f3eefe; color: #7c3aed; display: inline-flex; align-items: center; justify-content: center; }
.mcp-name { font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; font-weight: 700; color: var(--navy); }
.mcp-d { font-size: 12px; color: var(--text-muted); margin-left: auto; }
</style>
