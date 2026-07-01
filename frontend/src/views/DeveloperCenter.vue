<template>
  <div class="page-body dev-center">
    <Teleport to="#topbar-left">
      <span class="topbar-divider" />
      <span class="topbar-subtitle">管理接入凭证、查阅接口与 MCP 集成方式，均归属当前组织，切换组织即切换上下文</span>
    </Teleport>

    <!-- 子 tab -->
    <div class="seg">
      <button :class="{ on: tab === 'keys' }" @click="tab = 'keys'">🔑 API keys</button>
      <button :class="{ on: tab === 'api' }" @click="tab = 'api'">🔗 接口文档</button>
      <button :class="{ on: tab === 'mcp' }" @click="tab = 'mcp'">🧠 MCP 接入</button>
    </div>

    <!-- scope 提示 -->
    <div class="scope-note" :class="{ gov: isPlatform }">
      <template v-if="isPlatform">
        🛡 <span><b>全平台定向治理（破玻璃）</b> —— 出于最小权限，平台<b>不浏览全量 key</b>；按 key 名称/前缀定向查询要吊销的 key，吊销须填原因并记审计，不替组织创建或编辑。</span>
      </template>
      <template v-else>
        🛡 <span>当前展示 <b>{{ orgName }}</b> 的 API key。在此创建的密钥<b>自动绑定本组织</b>，调用时只能访问本组织（及公开）知识库，无需手动传组织。</span>
      </template>
    </div>

    <!-- ============ API keys ============ -->
    <div v-show="tab === 'keys'" class="card card-pad">
      <!-- 组织态：管自己组织的 key -->
      <template v-if="!isPlatform">
        <div class="desc">下表是<b>本组织</b>的全部 API key。Key 仅在创建时可见可复制，请妥善保存，不要暴露在前端代码中。</div>
        <table>
          <thead><tr><th>名称</th><th>Key</th><th>创建日期</th><th>最近使用</th><th></th></tr></thead>
          <tbody>
            <tr v-if="!keys.length"><td colspan="5" class="empty">{{ loading ? '加载中…' : '暂无 API key' }}</td></tr>
            <tr v-for="k in keys" :key="k.id">
              <td class="kname">{{ k.keyName }} <span v-if="!k.enabled" class="tag t-off">已吊销</span></td>
              <td class="kcode">{{ k.keyMasked }}</td>
              <td>{{ fmt(k.createdAt) }}</td>
              <td>{{ fmt(k.lastUsedAt) || '—' }}</td>
              <td><div class="row-act icons" v-if="canManage">
                <span class="ic" title="修改名称" @click="onRename(k)">✏️</span>
                <span class="ic" title="删除" @click="onDelete(k)">🗑️</span>
              </div><span v-else class="muted">只读</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="canManage" class="mt16"><button class="btn-primary" :disabled="creating" @click="onCreate">＋ 创建 API key</button></div>
      </template>

      <!-- 治理态：定向查询 + 吊销 -->
      <template v-else>
        <div class="desc">出于最小权限，平台<b>不浏览全量 key</b>。请按 <b>key 名称或前缀</b>（≥3 字符）定向查询要吊销的 key。</div>
        <div class="gov-search">
          <input v-model="govQuery" class="gov-input" placeholder="输入 key 名称或前缀，如 sk-rf-1d 或 招聘" @keyup.enter="onGovSearch" />
          <button class="btn-primary" :disabled="govLoading" @click="onGovSearch">查询</button>
        </div>
        <table v-if="govSearched">
          <thead><tr><th>名称</th><th>所属组织</th><th>Key</th><th>最近使用</th><th>状态</th><th></th></tr></thead>
          <tbody>
            <tr v-if="!govResults.length"><td colspan="6" class="empty">未匹配到 key</td></tr>
            <tr v-for="k in govResults" :key="k.id">
              <td class="kname">{{ k.keyName }}</td>
              <td>{{ k.orgName || (k.orgId ? 'org#' + k.orgId : '无组织') }}</td>
              <td class="kcode">{{ k.keyMasked }}</td>
              <td>{{ fmt(k.lastUsedAt) || '—' }}</td>
              <td><span class="tag" :class="k.enabled ? 't-on' : 't-off'">{{ k.enabled ? '正常' : '已吊销' }}</span></td>
              <td><div class="row-act"><span v-if="k.enabled" class="del" @click="onGovRevoke(k)">吊销</span><span v-else class="muted">—</span></div></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="gov-hint">🔍 输入检索词后点「查询」；为最小权限，平台不提供全量浏览。</div>
      </template>
    </div>

    <!-- ============ 接口文档 ============ -->
    <div v-show="tab === 'api'">
      <div class="grid2">
        <div class="card card-pad">
          <div class="sec-title">⚙️ 接入信息</div>
          <div class="sec-hint">把以下信息配置到你的客户端 / 服务端。</div>
          <div class="kv"><span class="k">Base URL</span><span class="v">{{ baseUrl }}<span class="copy" @click="copy($event, baseUrl)">复制</span></span></div>
          <div class="kv"><span class="k">认证方式</span><span class="v">X-API-Key: &lt;API key&gt;</span></div>
          <div class="kv"><span class="k">组织归属</span><span class="v">由 API key 自动绑定，无需传 X-Org-Id</span></div>
        </div>
        <div class="card card-pad">
          <div class="sec-title">🔗 核心接口</div>
          <div class="sec-hint">凭 API key（X-API-Key）调用，均按 key 绑定的组织过滤数据。</div>
          <div class="ep"><span class="m m-post">POST</span><span class="ep-path">/search</span><span class="ep-desc">混合检索（向量+BM25+精排）</span></div>
          <div class="ep"><span class="m m-post">POST</span><span class="ep-path">/answer</span><span class="ep-desc">RAG 应答</span></div>
          <div class="ep"><span class="m m-post">POST</span><span class="ep-path">/documents</span><span class="ep-desc">上传文档入库</span></div>
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
          <div class="kv"><span class="k">协议</span><span class="v">MCP（streamable HTTP + SSE）</span></div>
          <div class="kv"><span class="k">检索端点</span><span class="v">{{ mcpUrl }}<span class="copy" @click="copy($event, mcpUrl)">复制</span></span></div>
          <div class="kv"><span class="k">应答端点</span><span class="v">{{ sseUrl }}<span class="copy" @click="copy($event, sseUrl)">复制</span></span></div>
          <div class="sec-hint" style="margin-top:6px;">/mcp（streamable HTTP）提供检索类工具；answer_with_citations 走 /sse（SSE 传输）。</div>
          <div class="kv"><span class="k">认证</span><span class="v">X-API-Key: &lt;API key&gt;</span></div>
          <div class="kv"><span class="k">组织归属</span><span class="v">由 API key 自动绑定</span></div>
        </div>
        <div class="card card-pad">
          <div class="sec-title">🤝 适用客户端</div>
          <div class="sec-hint">凡支持 MCP 的客户端均可接入。</div>
          <div class="ep"><span class="m m-post">Agent</span><span class="ep-path">Claude Code、Cursor、Codex</span></div>
          <div class="ep"><span class="m m-get">应用</span><span class="ep-path">CareerMate</span></div>
          <div class="ep"><span class="m m-get">SDK</span><span class="ep-path">任意 MCP Client</span></div>
        </div>
      </div>
      <div class="card card-pad mt16">
        <div class="sec-title">🧠 MCP 配置</div>
        <div class="sec-hint">把以下配置加入客户端。Server 暴露的工具仅作用于<b>当前组织</b>。</div>
        <pre><button class="pre-copy" @click="copy($event, mcpText)">复制</button>{{ mcpText }}</pre>
        <div class="sec-title" style="margin:24px 0 6px;">可用 MCP 工具</div>
        <div class="mcp-tool" v-for="t in mcpTools" :key="t.name">
          <span class="mcp-ico">{{ t.ico }}</span><span class="mcp-name">{{ t.name }}</span><span class="mcp-d">{{ t.desc }}</span><span class="mcp-via">{{ t.via }}</span>
        </div>
      </div>
    </div>

    <!-- 创建表单：可视化选范围 / 知识库 / 过期 -->
    <div v-if="showCreateForm" class="mask" @click.self="showCreateForm = false">
      <div class="reveal cf">
        <div class="rv-head">
          <h3>创建 API key</h3>
          <span class="rv-x" @click="showCreateForm = false">×</span>
        </div>
        <div class="cf-body">
          <label class="cf-field">
            <span class="cf-label">名称 *</span>
            <input class="cf-input" v-model="createForm.keyName" placeholder="如 careermate-prod" />
          </label>

          <div class="cf-field">
            <span class="cf-label">可访问范围</span>
            <label class="cf-radio">
              <input type="radio" value="ORG_ALL" v-model="createForm.scopeMode" />
              本组织全部知识库（ORG_ALL）
            </label>
            <label class="cf-radio">
              <input type="radio" value="KB_LIST" v-model="createForm.scopeMode" />
              指定知识库（KB_LIST）
            </label>
          </div>

          <div v-if="createForm.scopeMode === 'KB_LIST'" class="cf-field">
            <span class="cf-label">选择知识库（仅本组织）</span>
            <div class="cf-kblist">
              <label v-for="kb in orgKbs" :key="kb.id" class="cf-kb">
                <input type="checkbox" :value="kb.id" v-model="createForm.allowedKbIds" />
                {{ kb.name }}
              </label>
              <div v-if="!orgKbs.length" class="cf-empty">本组织暂无知识库</div>
            </div>
          </div>

          <div class="cf-field">
            <span class="cf-label">权限级别</span>
            <span class="cf-static">只读（READ）· 本期仅支持只读</span>
          </div>

          <div class="cf-field">
            <span class="cf-label">有效期</span>
            <label class="cf-radio">
              <input type="radio" value="never" v-model="createForm.expiresMode" /> 永不过期
            </label>
            <label class="cf-radio">
              <input type="radio" value="date" v-model="createForm.expiresMode" /> 指定过期日期
            </label>
            <input
              v-if="createForm.expiresMode === 'date'"
              type="date"
              class="cf-date"
              v-model="createForm.expiresAt"
            />
          </div>
        </div>
        <div class="rv-foot">
          <button class="btn" @click="showCreateForm = false">取消</button>
          <button class="btn-primary" :disabled="creating" @click="submitCreate">创建</button>
        </div>
      </div>
    </div>

    <!-- 创建成功：弹窗一次性展示明文 key -->
    <div v-if="showReveal" class="mask" @click.self="closeReveal">
      <div class="reveal">
        <div class="rv-head">
          <h3>创建 API key</h3>
          <span class="rv-x" @click="closeReveal">×</span>
        </div>
        <p class="rv-msg">请将此 API key 保存在安全且易于访问的地方。出于安全原因，你将<b>无法通过 API keys 管理界面再次查看它</b>。如果你丢失了这个 key，将需要重新创建。</p>
        <div class="rv-key"><code>{{ newKey }}</code></div>
        <div class="rv-foot">
          <button class="btn" @click="closeReveal">关闭</button>
          <button class="btn-primary" @click="copy($event, newKey)">复制</button>
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
import { listApiKeys, createApiKey, renameApiKey, deleteApiKey, governanceSearchKeys, revokeApiKey } from '../api/apikey'
import { listKb } from '../api/kb'

const { current, isPlatform, currentOrgId } = useOrg()
const toast = useToast()

const tab = ref('keys')
const keys = ref([])
const loading = ref(false)
const creating = ref(false)
const newKey = ref('')
const showReveal = ref(false)
// 创建表单（可视化选范围/KB/过期）
const showCreateForm = ref(false)
const orgKbs = ref([])
const createForm = ref({
  keyName: '',
  scopeMode: 'ORG_ALL',
  allowedKbIds: [],
  accessLevel: 'READ',
  expiresMode: 'never',
  expiresAt: '',
})
// 定向治理（平台破玻璃）
const govQuery = ref('')
const govResults = ref([])
const govSearched = ref(false)
const govLoading = ref(false)

// 仅当前组织 OWNER/ADMIN 可管理 key（员工只读，与后端一致）。
const canManage = computed(() => {
  const r = current.value?.myRole
  return !isPlatform.value && (r === 'OWNER' || r === 'ADMIN')
})
const orgName = computed(() => current.value?.name || '当前组织')
// 对外公开的线上域名（开发者按此接入，非本地 dev 地址）。接口/MCP 文档全平台一致，
// 组织由 API key 自动绑定，调用方无需传 X-Org-Id —— 故文档为静态、不随组织变。
const PUBLIC_BASE = 'https://ragforge.net'
const baseUrl = `${PUBLIC_BASE}/api/v1`
const mcpUrl = `${PUBLIC_BASE}/mcp`
// /mcp = streamable HTTP，仅检索类工具；/sse = SSE 传输，含应答类工具（answer_with_citations）。
const sseUrl = `${PUBLIC_BASE}/sse`
const curlText =
  `curl ${baseUrl}/search \\\n` +
  `  -H "X-API-Key: <API key>" \\\n` +
  `  -H "Content-Type: application/json" \\\n` +
  `  -d '{ "query": "Java 高并发经验", "strategy": "hybrid", "topK": 5 }'`
const mcpText =
  `{\n  "mcpServers": {\n    "ragforge": {\n      "url": "${mcpUrl}",\n` +
  `      "headers": {\n        "X-API-Key": "<API key>"\n      }\n    }\n  }\n}`
// 与后端 @Tool 定义保持一致（工具名即客户端可调用名）。via 标明支持的端点：
// /mcp（streamable HTTP）仅检索类；answer_with_citations 仅 /sse（SSE 传输）。
const mcpTools = [
  { ico: '🔍', name: 'search_knowledge', desc: '在可访问知识库内混合检索（向量+关键词），返回相关片段', via: '/mcp · /sse' },
  { ico: '📚', name: 'list_knowledge_bases', desc: '列出可访问的知识库（含名称、文档数、片段数）', via: '/mcp · /sse' },
  { ico: '💬', name: 'answer_with_citations', desc: '用知识库回答问题，返回带引用的答案（含图片 URL）', via: '仅 /sse' },
]

function fmt(s) {
  return s ? String(s).slice(0, 10) : ''
}

async function reload() {
  loading.value = true
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
  createForm.value = {
    keyName: '',
    scopeMode: 'ORG_ALL',
    allowedKbIds: [],
    accessLevel: 'READ',
    expiresMode: 'never',
    expiresAt: '',
  }
  showCreateForm.value = true
  // 拉本组织知识库供 KB_LIST 选择（listKb 已按当前组织过滤）
  try {
    const res = await listKb()
    orgKbs.value = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
  } catch {
    orgKbs.value = []
  }
}

async function submitCreate() {
  if (creating.value) return
  const name = createForm.value.keyName.trim()
  if (!name) {
    toast.warning('请输入 API key 名称')
    return
  }
  if (createForm.value.scopeMode === 'KB_LIST' && createForm.value.allowedKbIds.length === 0) {
    toast.warning('指定知识库范围时，请至少选择一个知识库')
    return
  }
  const payload = { keyName: name, scopeMode: createForm.value.scopeMode, accessLevel: 'READ' }
  if (createForm.value.scopeMode === 'KB_LIST') {
    payload.allowedKbIds = createForm.value.allowedKbIds
  }
  if (createForm.value.expiresMode === 'date') {
    if (!createForm.value.expiresAt) {
      toast.warning('请选择过期日期')
      return
    }
    payload.expiresAt = `${createForm.value.expiresAt}T00:00:00`
  }
  creating.value = true
  try {
    const res = await createApiKey(payload)
    const created = res?.data ?? res
    newKey.value = created?.apiKey || ''
    showCreateForm.value = false
    showReveal.value = true
    await reload()
  } catch (e) {
    /* 全局拦截提示 */
  } finally {
    creating.value = false
  }
}

function closeReveal() {
  showReveal.value = false
  newKey.value = ''
}

async function onRename(k) {
  const name = await confirmDialog({
    title: '修改 API key 名称',
    message: '名称',
    icon: '',
    input: true,
    inputValue: k.keyName,
    inputPlaceholder: '输入 API key 的名称',
    confirmText: '保存',
  })
  if (!name || String(name).trim() === k.keyName) return
  await renameApiKey(k.id, String(name).trim())
  await reload()
  toast.success('已修改名称')
}

async function onGovSearch() {
  const q = govQuery.value.trim()
  if (q.length < 3) {
    toast.error('请输入至少 3 个字符的 key 名称或前缀')
    return
  }
  govLoading.value = true
  try {
    const res = await governanceSearchKeys(q)
    govResults.value = Array.isArray(res?.data) ? res.data : []
    govSearched.value = true
  } catch (e) {
    /* 全局拦截提示 */
  } finally {
    govLoading.value = false
  }
}

async function onGovRevoke(k) {
  const reason = await confirmDialog({
    title: '吊销 API key',
    message: `确认吊销「${k.keyName}」（${k.orgName || '无组织'}）？吊销后该 key 立即失效。请填写吊销原因（将记入审计）。`,
    icon: '',
    input: true,
    inputPlaceholder: '吊销原因，如：疑似泄露 / 违规使用',
    confirmText: '吊销',
  })
  if (!reason) return
  await revokeApiKey(k.id, String(reason).trim())
  await onGovSearch()
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
.row-act.icons { gap: 16px; } .row-act.icons .ic { font-size: 16px; line-height: 1; opacity: .85; cursor: pointer; }
.row-act.icons .ic:hover { opacity: 1; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 16px; border: none; border-radius: 9px; background: var(--primary); color: #fff; font-size: 13px; font-weight: 700; cursor: pointer; }
.btn-primary:disabled { opacity: .6; cursor: not-allowed; }
.btn { height: 30px; padding: 0 12px; border: 1px solid var(--border); background: #fff; border-radius: 8px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
.mt16 { margin-top: 16px; }
.gov-search { display: flex; gap: 10px; margin: 4px 0 18px; }
.gov-input { flex: 1; max-width: 460px; height: 38px; padding: 0 14px; border: 1px solid var(--border); border-radius: 9px; font-size: 13px; outline: none; }
.gov-input:focus { border-color: var(--primary); }
.gov-hint { padding: 28px 0; text-align: center; color: var(--text-muted); font-size: 13px; }

.newkey { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 12px; padding: 12px 14px; margin-bottom: 16px; }
.nk-h { font-size: 12.5px; font-weight: 700; color: #15803d; margin-bottom: 8px; }
.nk-row { display: flex; align-items: center; gap: 10px; }
.nk-row code { flex: 1; font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; color: var(--navy); word-break: break-all; }
.nk-tip { margin-top: 8px; font-size: 11.5px; color: #b45309; line-height: 1.6; } .nk-tip b { color: #b45309; }

/* 创建成功 reveal 弹窗（OpenAI 式） */
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.reveal { width: 480px; max-width: calc(100vw - 40px); background: #fff; border-radius: 16px; box-shadow: 0 20px 50px rgba(15, 31, 61, .25); padding: 22px 24px; }
.rv-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.rv-head h3 { margin: 0; font-size: 17px; font-weight: 700; color: var(--navy); }
.rv-x { font-size: 22px; color: var(--text-muted); cursor: pointer; line-height: 1; }
.rv-msg { font-size: 13px; color: var(--gray); line-height: 1.7; margin: 0 0 16px; }
.rv-msg b { color: var(--slate); }
.rv-key { background: #f5f7fa; border: 1px solid var(--border); border-radius: 10px; padding: 13px 14px; margin-bottom: 18px; }
.rv-key code { font-family: ui-monospace, Menlo, monospace; font-size: 13px; color: var(--navy); word-break: break-all; }
.rv-foot { display: flex; justify-content: flex-end; gap: 10px; }
.rv-foot .btn { height: 38px; padding: 0 18px; }
.rv-foot .btn-primary { height: 38px; background: #0f1726; }

/* 创建表单 */
.reveal.cf { width: 520px; }
.cf-body { display: flex; flex-direction: column; gap: 16px; margin: 6px 0 18px; max-height: 60vh; overflow-y: auto; }
.cf-field { display: flex; flex-direction: column; gap: 7px; }
.cf-label { font-size: 12.5px; font-weight: 600; color: var(--slate); }
.cf-input { border: 1px solid var(--border); border-radius: 9px; padding: 9px 12px; font-size: 13px; }
.cf-input:focus { outline: none; border-color: var(--primary); }
.cf-radio { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; color: var(--slate); cursor: pointer; }
.cf-radio input { width: 15px; height: 15px; }
.cf-static { font-size: 12.5px; color: var(--text-muted); }
.cf-kblist { display: flex; flex-direction: column; gap: 6px; max-height: 180px; overflow-y: auto; border: 1px solid var(--border); border-radius: 9px; padding: 10px 12px; }
.cf-kb { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; color: var(--slate); cursor: pointer; }
.cf-kb input { width: 15px; height: 15px; }
.cf-empty { font-size: 12.5px; color: var(--text-muted); padding: 4px 0; }
.cf-date { border: 1px solid var(--border); border-radius: 9px; padding: 8px 10px; font-size: 13px; width: fit-content; }

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
.mcp-via { flex: 0 0 auto; margin-left: 10px; font-family: ui-monospace, Menlo, monospace; font-size: 11px; font-weight: 700; color: #7c3aed; background: #f3eefe; border-radius: 6px; padding: 2px 8px; white-space: nowrap; }
</style>
