import { reactive, computed, readonly } from 'vue'
import { listOrgs } from '../api/org'

// 全局「当前组织」上下文（Claude Code 式：一切皆组织）。
// 个人组织是后端真实的 INDIVIDUAL 组织（不再有 org_id=null）；后端列表里带 personal:true 标记。
// 当前组织 id 持久化 localStorage，由 api/request.js 注入 X-Org-Id；平台视图走破玻璃。
const STORAGE_KEY = 'ragforge.currentOrgId'
export const PLATFORM_ID = 'platform'
const PLATFORM = { id: PLATFORM_ID, name: '全平台视图', slug: 'platform', myRole: 'ADMIN', platform: true }

function readStored() {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === PLATFORM_ID) return PLATFORM_ID
    return v === null || v === 'null' || v === '' ? null : Number(v)
  } catch {
    return null
  }
}

function persist(id) {
  try {
    localStorage.setItem(STORAGE_KEY, id === null || id === undefined ? 'null' : String(id))
  } catch {
    /* localStorage 不可用时忽略 */
  }
}

const state = reactive({
  orgs: [],
  currentId: readStored(),
  loaded: false,
})

function individualOrg() {
  return state.orgs.find((o) => o.personal) || null
}

function systemOrg() {
  // 系统组织仅在超管的组织列表里出现（后端按成员身份返回），普通用户列表中没有。
  return state.orgs.find((o) => o.type === 'SYSTEM' || o.id === 0) || null
}

// 默认落地组织：超管默认落「系统组织」（治理工作区），其余用户落个人组织。
// 个人组织仍是超管的私人空间，可随时从组织切换器切回。
function defaultOrgId() {
  const sys = systemOrg()
  if (sys) return sys.id
  const ind = individualOrg()
  return ind ? ind.id : null
}

export function useOrg() {
  const current = computed(() => {
    if (state.currentId === PLATFORM_ID) return PLATFORM
    return state.orgs.find((o) => o.id === state.currentId) || individualOrg() || {}
  })
  return {
    state: readonly(state),
    orgs: computed(() => state.orgs),
    current,
    currentOrgId: computed(() => current.value.id ?? null),
    isPersonal: computed(() => !!current.value.personal),
    // 系统组织（org_id=0，type=SYSTEM）：超管治理工作区，成员皆超管。跨组织「提权破玻璃」仅此组织下可用。
    isSystem: computed(() => current.value.type === 'SYSTEM' || current.value.id === 0),
    isPlatform: computed(() => state.currentId === PLATFORM_ID),
    /** 拉取我的组织（含个人组织）；当前选中失效则回退到默认组织（超管=系统组织，其余=个人组织）。 */
    async load(opts = {}) {
      try {
        const res = await listOrgs()
        const list = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
        state.orgs = list
        const defId = defaultOrgId()
        if (state.currentId === PLATFORM_ID) {
          // 全局「全平台视图」已下线：任何残留 platform 一律回退默认组织（超管=系统组织）。
          // 跨组织改由各页面的 Tab 级「提权查看全平台」开关承载。
          state.currentId = defId
          persist(defId)
        } else if (!list.some((o) => o.id === state.currentId)) {
          // 选中已失效（含首次登录的 null / 换了用户）→ 回退默认组织：
          // 超管落系统组织（治理工作区），其余用户落个人组织。
          state.currentId = defId
          persist(defId)
        }
      } finally {
        state.loaded = true
      }
    },
    /** 切换当前组织。 */
    setCurrent(id) {
      const next = id === undefined ? (individualOrg()?.id ?? null) : id
      if (next === state.currentId) return
      state.currentId = next
      persist(next)
    },
    reset() {
      state.orgs = []
      state.currentId = null
      persist(null)
      state.loaded = false
    },
  }
}

// 接受组织邀请后（NotificationBell 发出 membership-changed）免整页刷新地重载组织列表，
// 让组织切换器立即出现新加入的组织。模块级注册一次即可（ES 模块单例）。
if (typeof window !== 'undefined') {
  window.addEventListener('ragforge:membership-changed', () => {
    useOrg()
      .load()
      .catch(() => {})
  })

  // 跨 tab 组织上下文同步：currentOrgId 存于 origin 级 localStorage，OrgSwitcher 只 reload 当前 tab，
  // 其它 tab 的当前组织会被静默改变（下次请求带新组织，页面却仍是旧组织数据 → 操作误归属）。
  // storage 事件只在“发生变更之外的其它 tab”触发：currentOrgId 一旦变化，本 tab 整页重载，与切换 tab 对齐。
  window.addEventListener('storage', (e) => {
    if (e.key === STORAGE_KEY && e.oldValue !== e.newValue) {
      state.currentId = readStored()
      window.location.reload()
    }
  })
}
