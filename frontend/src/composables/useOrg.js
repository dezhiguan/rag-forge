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
    isPlatform: computed(() => state.currentId === PLATFORM_ID),
    /** 拉取我的组织（含个人组织）；当前选中失效则回退到个人组织。 */
    async load(opts = {}) {
      try {
        const res = await listOrgs()
        const list = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
        state.orgs = list
        const ind = individualOrg()
        const indId = ind ? ind.id : null
        if (state.currentId === PLATFORM_ID) {
          // 全平台仅超管可用：非超管残留 platform → 回退个人组织。
          if (!opts.isAdmin) {
            state.currentId = indId
            persist(indId)
          }
        } else if (!list.some((o) => o.id === state.currentId)) {
          // 选中已失效（含旧的 null / 换了用户）→ 回退个人组织。
          state.currentId = indId
          persist(indId)
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
}
