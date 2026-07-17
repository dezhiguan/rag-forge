<template>
  <div class="page-body dev-center">
    <Teleport to="#topbar-left">
      <span class="topbar-divider" />
      <span class="topbar-subtitle">管理接入凭证、查阅接口与 MCP 集成方式，均归属当前组织，切换组织即切换上下文</span>
    </Teleport>
    <Teleport to="#topbar-right">
      <ElevationToggle page-name="开发者中心" @change="reload" />
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
          <thead><tr><th>名称</th><th>权限</th><th>可访问范围</th><th>Key</th><th>有效期</th><th>最近使用</th><th></th></tr></thead>
          <tbody>
            <tr v-if="!keys.length"><td colspan="7" class="empty">{{ loading ? '加载中…' : '暂无 API key' }}</td></tr>
            <tr v-for="k in keys" :key="k.id">
              <td class="kname">{{ k.keyName }}
                <span v-if="!k.enabled" class="tag t-off">已吊销</span></td>
              <td><span class="tag" :class="k.accessLevel === 'WRITE' ? 't-write' : 't-read'">{{ k.accessLevel === 'WRITE' ? '读写' : '只读' }}</span></td>
              <td>
                <span v-if="k.scopeMode === 'KB_LIST'" class="scope-list" @click="openScope(k)">🎯 指定 {{ kbIdsOf(k).length }} 个库</span>
                <span v-else class="scope-all">全部知识库</span>
              </td>
              <td class="kcode">{{ k.keyMasked }}</td>
              <td><span class="exp" :class="expiryClass(k)">{{ expiryLabel(k) }}</span></td>
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
      <div class="cbar">
        <div class="cbar-item">
          <div class="cbar-k"><span class="dot"></span>Base URL</div>
          <div class="cbar-v">{{ baseUrl }}<span class="copy" @click="copy($event, baseUrl)">复制</span></div>
        </div>
        <div class="cbar-item">
          <div class="cbar-k"><span class="dot"></span>认证方式</div>
          <div class="cbar-v">X-API-Key: &lt;API key&gt;</div>
        </div>
        <div class="cbar-item">
          <div class="cbar-k"><span class="dot"></span>组织归属</div>
          <div class="cbar-v plain">由 API key 自动绑定，无需传 X-Org-Id</div>
        </div>
      </div>

      <div class="doc-grid mt16">
        <div class="card card-pad">
          <div class="sec-title">🔗 核心接口</div>
          <div class="sec-hint">点击接口查看入参 / 出参与示例 →</div>
          <button v-for="e in apiList" :key="e.key" class="ep-btn" :class="{ on: apiSel === e.key }" @click="selectApi(e.key)">
            <div class="ep-row1">
              <span class="m m-post">{{ e.method }}</span>
              <span class="ep-path">{{ e.path }}</span>
              <span v-if="e.badge" class="ep-badge sse">{{ e.badge }}</span>
              <span v-if="e.lock" class="ep-badge lock">{{ e.lock }}</span>
            </div>
            <div class="ep-desc2">{{ e.desc }}</div>
          </button>
        </div>

        <div class="card card-pad doc-detail">
          <div class="dt-head"><span class="m m-post">POST</span><span class="dt-path">{{ apiCur.path }}</span></div>
          <div class="dt-desc">{{ apiCur.desc }}</div>

          <div class="subtabs">
            <button :class="{ on: apiPane === 'req' }" @click="apiPane = 'req'">请求参数<i>{{ apiCur.req.length }}</i></button>
            <button :class="{ on: apiPane === 'res' }" @click="apiPane = 'res'">响应参数<i>{{ apiCur.res.length }}</i></button>
            <button :class="{ on: apiPane === 'ex' }" @click="apiPane = 'ex'">调用示例</button>
          </div>

          <div v-show="apiPane === 'req'"><ParamTable :rows="apiCur.req" /></div>
          <div v-show="apiPane === 'res'"><ParamTable :rows="apiCur.res" /></div>
          <div v-show="apiPane === 'ex'">
            <div class="cb-label">请求 · cURL</div>
            <pre><button class="pre-copy" @click="copy($event, apiCur.curl)">复制</button>{{ apiCur.curl }}</pre>
            <div class="cb-label">{{ apiCur.respTitle || '响应 · JSON' }}</div>
            <pre><button class="pre-copy" @click="copy($event, apiCur.resp)">复制</button>{{ apiCur.resp }}</pre>
          </div>

          <div class="doc-note">💡 字段类型旁的 <span class="rq">必填</span> / <span class="op">可选</span> 标签帮助快速判断最小请求体。</div>
        </div>
      </div>
    </div>

    <!-- ============ MCP 接入 ============ -->
    <div v-show="tab === 'mcp'">
      <div class="cbar">
        <div class="cbar-item">
          <div class="cbar-k"><span class="dot"></span>MCP Endpoint</div>
          <div class="cbar-v">{{ mcpUrl }}<span class="copy" @click="copy($event, mcpUrl)">复制</span></div>
        </div>
        <div class="cbar-item">
          <div class="cbar-k"><span class="dot"></span>传输方式</div>
          <div class="cbar-v plain">Streamable HTTP · 无状态</div>
        </div>
        <div class="cbar-item">
          <div class="cbar-k"><span class="dot"></span>鉴权头</div>
          <div class="cbar-v">X-API-Key: &lt;API key&gt;</div>
        </div>
      </div>
      <div class="cbar-notes">
        <span>已下线旧的 <code class="inl">/sse</code> 端点，请统一使用 <code class="inl">/mcp</code>。</span>
        <span>📥 入库不在 MCP 工具内，请用 REST <code class="inl">POST /api/v1/documents</code>（需 WRITE key）。</span>
      </div>

      <div class="doc-grid mt16">
        <div class="card card-pad">
          <div class="sec-title">🧩 可用工具</div>
          <div class="sec-hint">点击工具查看参数与返回 →</div>
          <button v-for="t in mcpList" :key="t.key" class="ep-btn" :class="{ on: mcpSel === t.key }" @click="selectMcp(t.key)">
            <div class="ep-row1">
              <span class="m m-tool">TOOL</span>
              <span class="ep-path">{{ t.name }}</span>
            </div>
            <div class="ep-desc2">{{ t.desc }}</div>
          </button>
        </div>

        <div class="card card-pad doc-detail">
          <div class="dt-head"><span class="m m-tool">TOOL</span><span class="dt-path">{{ mcpCur.name }}</span></div>
          <div class="dt-desc">{{ mcpCur.desc }}</div>

          <div class="subtabs">
            <button :class="{ on: mcpPane === 'args' }" @click="mcpPane = 'args'">参数<i>{{ mcpCur.args.length }}</i></button>
            <button :class="{ on: mcpPane === 'ret' }" @click="mcpPane = 'ret'">返回</button>
            <button :class="{ on: mcpPane === 'ex' }" @click="mcpPane = 'ex'">调用示例</button>
          </div>

          <div v-show="mcpPane === 'args'"><ParamTable :rows="mcpCur.args" /></div>
          <div v-show="mcpPane === 'ret'"><div class="dt-ret">{{ mcpCur.ret }}</div></div>
          <div v-show="mcpPane === 'ex'">
            <div class="cb-label">① 客户端配置（粘到 Claude Desktop / Cursor 并重启，三工具共用）</div>
            <pre><button class="pre-copy" @click="copy($event, mcpText)">复制</button>{{ mcpText }}</pre>
            <div class="cb-label">② 工具调用 · tools/call</div>
            <pre><button class="pre-copy" @click="copy($event, mcpCur.call)">复制</button>{{ mcpCur.call }}</pre>
            <div class="cb-label">③ 返回 · result</div>
            <pre><button class="pre-copy" @click="copy($event, mcpCur.result)">复制</button>{{ mcpCur.result }}</pre>
          </div>

          <div class="doc-note">💡 「调用示例」按 ①配置 → ②调用 → ③返回 三步；配置三工具共用同一份，替换成你的 API key 即可。</div>
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
            <input class="cf-input" v-model="createForm.keyName" maxlength="100" placeholder="如 careermate-prod" />
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
            <label class="cf-radio">
              <input type="radio" value="READ" v-model="createForm.accessLevel" />
              只读（READ）· 检索 / 应答
            </label>
            <label class="cf-radio">
              <input type="radio" value="WRITE" v-model="createForm.accessLevel" />
              读写（WRITE）· 额外可调 /documents 入库
            </label>
            <div v-if="createForm.accessLevel === 'WRITE'" class="cf-hint">
              ⚠ 写入密钥可向上方所选范围内的知识库入库，产生存储与向量化成本（计入本组织用量）。请妥善保管。
            </div>
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

    <!-- 可访问范围：查看该 key 具体授权的知识库 -->
    <div v-if="scopeDialog.open" class="mask" @click.self="closeScope">
      <div class="reveal">
        <div class="rv-head">
          <h3>可访问的知识库</h3>
          <span class="rv-x" @click="closeScope">×</span>
        </div>
        <p class="rv-msg">密钥 <b>{{ scopeDialog.keyName }}</b> 被授权访问以下 <b>{{ scopeDialog.items.length }}</b> 个知识库：</p>
        <div class="kb-list">
          <div v-for="it in scopeDialog.items" :key="it.id" class="kb-item">
            <span class="kb-ico">📚</span>
            <div class="kb-body">
              <div class="kb-name" :class="{ 'kb-gone': !it.exists }">{{ it.name }}</div>
              <div v-if="it.exists" class="kb-meta">{{ it.docCount }} 文档 · {{ it.chunkCount }} 片段</div>
              <div v-else class="kb-meta kb-gone">已删除或当前无权访问</div>
            </div>
          </div>
          <div v-if="!scopeDialog.items.length" class="kb-empty">该 key 未授权任何知识库</div>
        </div>
        <div class="rv-foot">
          <button class="btn" @click="closeScope">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useOrg } from '../composables/useOrg'
import { useElevation } from '../composables/useElevation'
import { useToast } from '../composables/useToast'
import { confirm as confirmDialog } from '../composables/useConfirm'
import ElevationToggle from '../components/ElevationToggle.vue'
import ParamTable from '../components/ParamTable.vue'
import { listApiKeys, createApiKey, renameApiKey, deleteApiKey, governanceSearchKeys, revokeApiKey } from '../api/apikey'
import { listKb } from '../api/kb'

const { current, currentOrgId } = useOrg()
// 治理态由「提权（破玻璃）」驱动，而非已下线的全局全平台视图。
// 提权后 = 全平台定向治理（不浏览全量 key，按名称/前缀定向查询 + 吊销）；未提权 = 管当前组织自己的 key。
const { active: elevationActive } = useElevation()
const isPlatform = elevationActive
const toast = useToast()

const tab = ref('keys')
const keys = ref([])
const loading = ref(false)
const creating = ref(false)
const newKey = ref('')
const showReveal = ref(false)
// 「可访问范围」查看具体授权知识库的弹窗；kbIndex 懒加载本组织 KB（id→kb）供解析库名。
const scopeDialog = ref({ open: false, keyName: '', items: [] })
const kbIndex = ref(null)
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
// /mcp = streamable HTTP（无状态），暴露全部三个工具，含 answer_with_citations。SSE 传输已下线。
const mcpText =
  `{\n  "mcpServers": {\n    "ragforge": {\n      "url": "${mcpUrl}",\n` +
  `      "headers": {\n        "X-API-Key": "<API key>"\n      }\n    }\n  }\n}`

// ============ 接口文档：可点选接口 + 联动入参/出参/示例 ============
// 字段全部对齐后端真实实现（SearchRequest / AnswerModels / IngestCommand 等），非臆造。
const apiSel = ref('search')
const apiPane = ref('req')
function selectApi(k) { apiSel.value = k; apiPane.value = 'req' }
const apiList = [
  { key: 'search', method: 'POST', path: '/search', desc: '多策略检索（向量 / 关键词 / 混合）' },
  { key: 'answer', method: 'POST', path: '/answer', desc: 'RAG 应答（检索 + 生成 + 引用）', badge: 'SSE 流式' },
  { key: 'documents', method: 'POST', path: '/documents', desc: '上传文档入库（multipart 表单）', lock: 'WRITE 密钥' },
]
const apiDocs = {
  search: {
    path: '/search',
    desc: '检索本组织知识库，支持向量 / 关键词 / 混合等多种策略，返回最相关的片段。返回统一 Result 信封（code / msg / data）。',
    req: [
      { n: 'query', t: 'string', r: true, d: '检索问题 / 关键词。' },
      { n: 'kbIds', t: 'array', r: false, d: '限定检索的知识库 id 列表（数字数组）；不传则检索该 key 全部可读库。' },
      { n: 'docIds', t: 'array', r: false, d: '限定文档 id 范围（数字数组）。' },
      { n: 'strategy', t: 'string', r: false, d: '检索策略。', enums: ['vector', 'keyword', 'rewrite', 'hybrid', 'full'], def: 'hybrid' },
      { n: 'topK', t: 'number', r: false, d: '返回片段数，1–50。', def: '8' },
      { n: 'vectorWeight', t: 'number', r: false, d: '向量权重，仅 hybrid 生效。', def: '0.55' },
      { n: 'rerankTopN', t: 'number', r: false, d: '精排候选数，1–50，仅 full 生效。', def: '5' },
      { n: 'filter', t: 'object', r: false, d: '过滤条件，如 { chunkType: [...] }。' },
    ],
    res: [
      { n: 'code', t: 'number', r: true, d: '状态码，0 表示成功。' },
      { n: 'msg', t: 'string', r: true, d: '提示信息。' },
      { n: 'data.results', t: 'array', r: true, d: '命中片段数组。' },
      { n: 'data.results[].docId', t: 'number', r: true, d: '文档 id（纯数字）。' },
      { n: 'data.results[].chunkId', t: 'number', r: true, d: '片段 id。' },
      { n: 'data.results[].filename', t: 'string', r: true, d: '来源文件名。' },
      { n: 'data.results[].content', t: 'string', r: true, d: '片段正文。' },
      { n: 'data.results[].vectorScore', t: 'number', r: false, d: '向量相似度得分。' },
      { n: 'data.results[].bm25Score', t: 'number', r: false, d: 'BM25 关键词得分。' },
      { n: 'data.results[].finalScore', t: 'number', r: true, d: '最终排序得分。' },
      { n: 'data.results[].imageUrl', t: 'string', r: false, d: '图片片段的时效访问 URL（如有）。' },
      { n: 'data.latencyMs', t: 'number', r: true, d: '检索总耗时（毫秒）。' },
      { n: 'data.strategy', t: 'string', r: true, d: '实际生效的检索策略。' },
    ],
    curl: `curl ${baseUrl}/search \\\n` +
      `  -H "X-API-Key: <API key>" \\\n` +
      `  -H "Content-Type: application/json" \\\n` +
      `  -d '{\n` +
      `        "query": "Java 高并发经验",\n` +
      `        "kbIds": [16],\n` +
      `        "strategy": "hybrid",\n` +
      `        "topK": 8\n` +
      `      }'`,
    respTitle: '响应 · JSON',
    resp: `{\n` +
      `  "code": 0, "msg": "ok",\n` +
      `  "data": {\n` +
      `    "results": [\n` +
      `      {\n` +
      `        "docId": 1024,\n` +
      `        "chunkId": 88231,\n` +
      `        "filename": "个人简历.pdf",\n` +
      `        "content": "负责千万级订单系统的高并发改造…",\n` +
      `        "vectorScore": 0.83,\n` +
      `        "bm25Score": 0.71,\n` +
      `        "finalScore": 0.912\n` +
      `      }\n` +
      `    ],\n` +
      `    "latencyMs": 83, "strategy": "hybrid"\n` +
      `  }\n` +
      `}`,
  },
  answer: {
    path: '/answer',
    desc: 'RAG 应答：先检索知识库，再由大模型生成带引用编号的回答，以 SSE（text/event-stream）流式返回。⚠️ 前置条件：目标 KB 的 answerMode 必须为 ON，否则返回 403 ANSWER_DISABLED；kbIds 必填（为空返回 400 KB_IDS_REQUIRED）。',
    req: [
      { n: 'kbIds', t: 'array', r: true, d: '目标知识库 id 列表（数字数组）。为空返回 400 KB_IDS_REQUIRED。' },
      { n: 'query', t: 'string', r: true, d: '用户问题。' },
      { n: 'retrievalStrategy', t: 'string', r: false, d: '检索策略。', def: 'hybrid' },
      { n: 'topK', t: 'number', r: false, d: '参与生成的片段数。', def: '10' },
      { n: 'maxTokens', t: 'number', r: false, d: '生成回答的最大 token。', def: '800' },
      { n: 'stream', t: 'boolean', r: false, d: '是否流式返回（SSE）。', def: 'true' },
      { n: 'answerMode', t: 'string', r: false, d: '应答模式。', def: 'ON' },
    ],
    res: [
      { n: 'answer', t: 'string', r: true, d: '模型生成的回答，正文内含 [1][2] 引用标记。' },
      { n: 'citations', t: 'array', r: true, d: '引用来源数组，编号与正文标记对应。' },
      { n: 'citations[].id', t: 'number', r: true, d: '引用编号。' },
      { n: 'citations[].docId', t: 'number', r: true, d: '被引用文档 id。' },
      { n: 'citations[].chunkId', t: 'number', r: true, d: '被引用片段 id。' },
      { n: 'citations[].textSnippet', t: 'string', r: true, d: '被引用片段摘录。' },
      { n: 'citations[].score', t: 'number', r: false, d: '相关度得分。' },
      { n: 'tokens', t: 'object', r: true, d: 'token 用量 { prompt, completion }。' },
      { n: 'latency', t: 'object', r: true, d: '分段耗时 { retrieval, llm, total }（毫秒）。' },
      { n: 'guardRailResult', t: 'string', r: false, d: '护栏结果，如 PASS / NO_CITATIONS / OUT_OF_SCOPE / PII_LEAK。' },
      { n: 'llmModel', t: 'string', r: false, d: '实际使用的模型。' },
      { n: 'retrieval', t: 'object', r: false, d: '底层检索结果（结构同 /search 的 data，含 results/latencyMs 等）。' },
    ],
    curl: `curl -N ${baseUrl}/answer \\\n` +
      `  -H "X-API-Key: <API key>" \\\n` +
      `  -H "Content-Type: application/json" \\\n` +
      `  -H "Accept: text/event-stream" \\\n` +
      `  -d '{\n` +
      `        "kbIds": [16],\n` +
      `        "query": "候选人有没有分布式事务经验？",\n` +
      `        "topK": 10\n` +
      `      }'`,
    respTitle: '响应 · SSE 事件流（text/event-stream）',
    resp: `# 每帧 = event: 事件名 + data: JSON，帧间空行分隔\n\n` +
      `event: retrieval      # 首个事件：先回传检索命中的 chunks\n` +
      `data: {"chunks": [{"chunkId": 88231, "docId": 1024, "filename": "个人简历.pdf", "content": "…"}]}\n\n` +
      `event: token          # 增量 token，可能出现多次\n` +
      `data: {"delta": "有。"}\n\n` +
      `event: token\n` +
      `data: {"delta": "候选人落地过 Seata AT 模式 [1]…"}\n\n` +
      `event: complete       # 最终事件，data 为完整应答；随后流关闭\n` +
      `data: {"answer":"…[1]…","citations":[{"id":1,"docId":1024,"textSnippet":"引入 Seata…","score":0.90}],"tokens":{"prompt":512,"completion":130},"latency":{"retrieval":80,"llm":1100,"total":1180},"guardRailResult":"PASS","llmModel":"qwen-max"}\n\n` +
      `# 出错时改发 error 事件（kbIds 为空 / 无可读库 / 护栏拦截）：\n` +
      `event: error\n` +
      `data: {"error": "KB_ACCESS_DENIED", "message": "无可读知识库"}`,
  },
  documents: {
    path: '/documents',
    desc: '上传文档入库（multipart/form-data 表单，非 JSON）：file 部分传文件、meta 部分传元数据 JSON。自动切片 + 向量化。需 WRITE 级 API key。返回的 status 是「登记态」（入库去重结果，即时返回）；文档解析是异步的，要跟踪切片/向量化进度请轮询 GET /documents/{id}/status 的 parseStatus（PROCESSING / COMPLETED / FAILED）——注意二者是不同字段、不同状态机。',
    req: [
      { n: 'file', t: 'string', r: true, d: '【表单 part】文档文件（multipart 的 file 部分，即文档正文来源）。' },
      { n: 'meta', t: 'string', r: true, d: '【表单 part】元数据 JSON 字符串，反序列化为下列字段。' },
      { n: 'meta.kbId', t: 'number', r: true, d: '目标知识库 id。' },
      { n: 'meta.identity.externalId', t: 'string', r: false, d: '业务侧唯一标识，用于增量去重 / 覆盖更新。' },
      { n: 'meta.identity.sourceUrl', t: 'string', r: false, d: '来源 URL。' },
      { n: 'meta.identity.contentMd5', t: 'string', r: false, d: '内容 MD5。' },
      { n: 'meta.metadata', t: 'object', r: false, d: '自定义元数据。' },
      { n: 'meta.onConflict', t: 'string', r: false, d: '同一 externalId 冲突时的策略。', def: 'REJECT' },
    ],
    res: [
      { n: 'documentId', t: 'number', r: true, d: '入库后文档 id（纯数字，无 doc- 前缀）。' },
      { n: 'status', t: 'string', r: true, d: '登记态（入库去重结果，非解析进度）。解析进度另见 GET /documents/{id}/status 的 parseStatus。', enums: ['CREATED', 'SKIPPED', 'REPLACED'] },
    ],
    curl: `curl ${baseUrl}/documents \\\n` +
      `  -H "X-API-Key: <WRITE key>" \\\n` +
      `  -F 'file=@高级Java工程师-JD.txt' \\\n` +
      `  -F 'meta={"kbId":16,"identity":{"externalId":"jd-boss-88231"},"onConflict":"REPLACE"}'`,
    respTitle: '响应 · JSON',
    resp: `{\n  "documentId": 2048,\n  "status": "CREATED"\n}`,
  },
}
const apiCur = computed(() => apiDocs[apiSel.value])

// ============ MCP 工具：可点选工具 + 联动参数/返回/示例 ============
// 工具名与入参对齐 /mcp tools/list 实际注册（StreamableMcpController / RagForgeMcpTools）。
const mcpSel = ref('search_knowledge')
const mcpPane = ref('args')
function selectMcp(k) { mcpSel.value = k; mcpPane.value = 'args' }
const mcpList = [
  { key: 'search_knowledge', name: 'search_knowledge', desc: '混合检索知识库片段' },
  { key: 'answer_with_citations', name: 'answer_with_citations', desc: '用知识库回答（带引用）' },
  { key: 'list_knowledge_bases', name: 'list_knowledge_bases', desc: '列出可读知识库' },
]
const mcpDocs = {
  search_knowledge: {
    name: 'search_knowledge',
    desc: '在可读知识库中做混合检索（向量 + 关键词），返回相关片段；命中图片会附带时效访问 URL。策略固定 hybrid（客户端不可选）。',
    args: [
      { n: 'query', t: 'string', r: true, d: '检索问题 / 关键词。' },
      { n: 'kbIds', t: 'string', r: false, d: '逗号分隔的知识库 id 字符串，如 "15,16"；不填=搜所有可读库。' },
      { n: 'topK', t: 'number', r: false, d: '返回片段数，1–10。', def: '5' },
    ],
    ret: '返回编号片段列表文本（[n] 来源 + 内容，图片附 URL），回填给模型供其引用作答。参数错误 / 无可读库时返回 isError=true。',
    call: `{\n  "method": "tools/call",\n  "params": {\n    "name": "search_knowledge",\n    "arguments": {\n      "query": "Java 高并发经验",\n      "kbIds": "16",\n      "topK": 5\n    }\n  }\n}`,
    result: `{\n  "content": [ { "type": "text",\n    "text": "[1] 个人简历.pdf 负责千万级订单系统的高并发改造…" } ]\n}`,
  },
  answer_with_citations: {
    name: 'answer_with_citations',
    desc: '用知识库回答问题，返回带引用（含图片 URL）的答案。topK 固定 10、maxTokens 800、策略 hybrid，均写死，客户端不可传。',
    args: [
      { n: 'query', t: 'string', r: true, d: '用户问题。' },
      { n: 'kbIds', t: 'string', r: false, d: '逗号分隔的知识库 id 字符串，如 "15,16"；不填=所有可读库。' },
    ],
    ret: '返回文本：答案正文 + 「引用:」列表（含图片 URL）。',
    call: `{\n  "method": "tools/call",\n  "params": {\n    "name": "answer_with_citations",\n    "arguments": {\n      "query": "候选人有没有分布式事务经验？",\n      "kbIds": "16"\n    }\n  }\n}`,
    result: `{\n  "content": [ { "type": "text",\n    "text": "有。候选人落地过 Seata AT 模式 [1]…\\n引用: [1] 个人简历.pdf" } ]\n}`,
  },
  list_knowledge_bases: {
    name: 'list_knowledge_bases',
    desc: '列出当前 API key 可读的知识库（id / 名称 / 文档数 / 片段数 / 描述），供模型先了解有哪些库再检索。',
    args: [],
    ret: '返回知识库清单文本。',
    call: `{\n  "method": "tools/call",\n  "params": {\n    "name": "list_knowledge_bases",\n    "arguments": {}\n  }\n}`,
    result: `{\n  "content": [ { "type": "text",\n    "text": "16 岗位JD库 · 文档 214 · 片段 1806\\n17 公司情报库 · 文档 95 · 片段 640" } ]\n}`,
  },
}
const mcpCur = computed(() => mcpDocs[mcpSel.value])

function fmt(s) {
  return s ? String(s).slice(0, 10) : ''
}

// allowedKbIds 可能是 JSON 字符串("[1,2]")或数组，统一解析为 number[]。
function kbIdsOf(k) {
  const raw = k?.allowedKbIds
  const arr = Array.isArray(raw)
    ? raw
    : typeof raw === 'string' && raw.trim()
      ? (() => { try { const a = JSON.parse(raw); return Array.isArray(a) ? a : [] } catch { return [] } })()
      : []
  return arr.map(Number).filter((n) => !Number.isNaN(n))
}

// 有效期展示：永不过期 / 到期日；≤7 天预警、已过期置灰。
function daysToExpiry(k) {
  if (!k?.expiresAt) return null
  const exp = new Date(String(k.expiresAt).slice(0, 10))
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((exp.getTime() - today.getTime()) / 86400000)
}
function expiryLabel(k) {
  if (!k?.expiresAt) return '永不过期'
  const day = String(k.expiresAt).slice(0, 10)
  const d = daysToExpiry(k)
  if (d < 0) return `已过期（${day}）`
  if (d <= 7) return `${d} 天后到期`
  return `${day} 到期`
}
function expiryClass(k) {
  if (!k?.expiresAt) return 'exp-never'
  const d = daysToExpiry(k)
  if (d < 0) return 'exp-past'
  if (d <= 7) return 'exp-soon'
  return 'exp-date'
}

// 懒加载本组织 KB 建立 id→kb 索引（listKb 已按当前组织过滤）。
async function ensureKbIndex() {
  if (kbIndex.value) return kbIndex.value
  const m = new Map()
  try {
    const res = await listKb()
    const list = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
    for (const kb of list) m.set(Number(kb.id), kb)
  } catch {
    /* 全局拦截提示 */
  }
  kbIndex.value = m
  return m
}
async function openScope(k) {
  const ids = kbIdsOf(k)
  const idx = await ensureKbIndex()
  scopeDialog.value = {
    open: true,
    keyName: k.keyName,
    items: ids.map((id) => {
      const kb = idx.get(Number(id))
      return kb
        ? { id, name: kb.name, docCount: kb.docCount ?? 0, chunkCount: kb.chunkCount ?? 0, exists: true }
        : { id, name: `知识库 #${id}`, exists: false }
    }),
  }
}
function closeScope() {
  scopeDialog.value = { open: false, keyName: '', items: [] }
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
    toast.warning('请输入 API 密钥名称')
    return
  }
  if (createForm.value.scopeMode === 'KB_LIST' && createForm.value.allowedKbIds.length === 0) {
    toast.warning('指定知识库范围时，请至少选择一个知识库')
    return
  }
  const accessLevel = createForm.value.accessLevel === 'WRITE' ? 'WRITE' : 'READ'
  const payload = { keyName: name, scopeMode: createForm.value.scopeMode, accessLevel }
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
    toast.error('请输入至少 3 个字符的密钥名称或前缀')
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
.t-write { background: #fef3c7; color: #b45309; }
.t-read { background: var(--primary-soft); color: var(--primary); }

/* 可访问范围 */
.scope-all { color: var(--text-muted); }
.scope-list { color: var(--primary); font-weight: 600; cursor: pointer; border-bottom: 1px dashed var(--primary-border); padding-bottom: 1px; }
.scope-list:hover { border-bottom-color: var(--primary); }

/* 有效期 */
.exp { font-size: 13px; }
.exp-never { color: var(--text-muted); }
.exp-date { color: #c2410c; }
.exp-soon { color: #dc2626; font-weight: 600; }
.exp-past { color: var(--text-muted); text-decoration: line-through; }

/* 可访问范围弹窗内的 KB 列表 */
.kb-list { display: flex; flex-direction: column; gap: 8px; max-height: 340px; overflow-y: auto; margin-bottom: 4px; }
.kb-item { display: flex; align-items: center; gap: 10px; padding: 11px 12px; border: 1px solid var(--border); border-radius: 10px; }
.kb-ico { width: 30px; height: 30px; border-radius: 8px; background: var(--primary-soft); color: var(--primary); display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kb-name { font-weight: 600; color: var(--navy); font-size: 13.5px; }
.kb-meta { font-size: 11.5px; color: var(--text-muted); margin-top: 1px; }
.kb-gone { color: #dc2626; }
.kb-empty { text-align: center; color: var(--text-muted); font-size: 13px; padding: 18px 0; }
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
.cf-hint { font-size: 12px; color: #b45309; background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 8px 10px; line-height: 1.6; }
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

/* ===== 可点选接口/工具 + 联动详情面板 ===== */
.doc-grid { display: grid; grid-template-columns: 280px 1fr; gap: 16px; align-items: start; }
.doc-left { display: flex; flex-direction: column; gap: 16px; }
@media (max-width: 900px) { .doc-grid { grid-template-columns: 1fr; } }

/* 接入信息 / 服务端点：顶部瘦信息条，三字段等分分栏 */
.cbar { display: flex; align-items: stretch; flex-wrap: wrap; border: 1px solid var(--border); background: var(--surface); border-radius: 14px; box-shadow: var(--shadow-sm); overflow: hidden; }
.cbar-item { flex: 1; min-width: 220px; padding: 12px 18px; border-right: 1px solid var(--border); }
.cbar-item:last-child { border-right: 0; }
.cbar-k { font-size: 11px; color: var(--text-muted); margin-bottom: 5px; display: flex; align-items: center; gap: 6px; }
.cbar-k .dot { width: 5px; height: 5px; border-radius: 50%; background: var(--primary); flex: 0 0 auto; }
.cbar-v { font-family: ui-monospace, Menlo, monospace; font-size: 13px; font-weight: 600; color: var(--navy); word-break: break-all; }
.cbar-v.plain { font-family: inherit; }
.cbar-v .copy { margin-left: 10px; }
.cbar-notes { display: flex; flex-wrap: wrap; gap: 6px 24px; margin-top: 10px; padding: 0 2px; font-size: 12px; color: var(--text-muted); }
.inl { font-family: ui-monospace, Menlo, monospace; font-size: 12px; background: var(--primary-soft); border-radius: 4px; padding: 1px 5px; color: var(--navy); }

.ep-btn { display: block; width: 100%; text-align: left; border: 1.5px solid var(--border); background: var(--surface); border-radius: 12px; padding: 11px 13px; cursor: pointer; margin-top: 8px; font: inherit; transition: border-color .12s, background .12s; }
.ep-btn:first-of-type { margin-top: 0; }
.ep-btn:hover { border-color: var(--primary); }
.ep-btn.on { border-color: var(--primary); background: var(--primary-soft); }
.ep-btn:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.ep-row1 { display: flex; align-items: center; gap: 9px; }
.ep-desc2 { font-size: 12px; color: var(--text-muted); margin-top: 6px; }
.ep-badge { font-size: 10.5px; font-weight: 700; border-radius: 5px; padding: 2px 6px; }
.ep-badge.sse { color: #0369a1; background: #e0f2fe; }
.ep-badge.lock { color: #c2410c; background: #ffedd5; }
.m-tool { background: #f3e8ff; color: #7c3aed; }

.doc-detail { align-self: start; }
.dt-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.dt-path { font-family: ui-monospace, Menlo, monospace; font-size: 16px; font-weight: 700; color: var(--navy); word-break: break-all; }
.dt-desc { font-size: 12.5px; color: var(--gray); line-height: 1.7; margin-bottom: 14px; }
.dt-ret { font-size: 13px; color: var(--gray); line-height: 1.75; padding: 4px 2px; }

.subtabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border); margin-bottom: 14px; flex-wrap: wrap; }
.subtabs button { appearance: none; border: 0; background: none; cursor: pointer; font: inherit; font-size: 13px; font-weight: 600; color: var(--text-muted); padding: 8px 13px; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.subtabs button:hover { color: var(--slate); }
.subtabs button.on { color: var(--primary); border-bottom-color: var(--primary); }
.subtabs button i { font-style: normal; font-size: 11px; color: var(--text-muted); margin-left: 4px; }

.cb-label { font-size: 11px; font-weight: 700; color: var(--text-muted); margin: 14px 0 7px; }
.cb-label:first-child { margin-top: 4px; }
.doc-detail pre { margin: 0; }

.doc-note { margin-top: 14px; font-size: 12px; color: var(--text-muted); line-height: 1.6; }
.doc-note .rq { font-size: 11px; font-weight: 700; color: #dc2626; }
.doc-note .op { font-size: 11px; color: var(--text-muted); }
</style>
