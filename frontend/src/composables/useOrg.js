import { reactive, computed, readonly } from 'vue'
import { listOrgs } from '../api/org'

// 全局「当前组织」上下文（GitHub 式：个人 + 所属组织）。
// 个人组织在后端无实体（org_id 为 null），这里前端合成一个 personal 条目。
// 当前组织 id 持久化到 localStorage，并由 api/request.js 注入 X-Org-Id 请求头。
const STORAGE_KEY = 'ragforge.currentOrgId'
const PERSONAL = { id: null, name: '个人', slug: 'personal', myRole: 'OWNER', personal: true }
// 平台超管「全平台视图」哨兵：选中时走破玻璃(X-Admin-Override)，由后端返回全平台聚合。
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
  orgs: [PERSONAL],
  currentId: readStored(),
  loaded: false,
})

export function useOrg() {
  const current = computed(() => {
    if (state.currentId === PLATFORM_ID) return PLATFORM
    return state.orgs.find((o) => o.id === state.currentId) || PERSONAL
  })
  return {
    state: readonly(state),
    orgs: computed(() => state.orgs),
    current,
    currentOrgId: computed(() => state.currentId),
    isPersonal: computed(() => state.currentId === null),
    isPlatform: computed(() => state.currentId === PLATFORM_ID),
    /**
     * 拉取我的组织并合成个人条目；校验当前选中并在失效时回退个人。
     * @param {{isAdmin?: boolean}} opts isAdmin=false 时清除残留的 platform 选择（防跨用户串台）。
     */
    async load(opts = {}) {
      try {
        const res = await listOrgs()
        const list = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
        state.orgs = [PERSONAL, ...list]
        // 全平台仅超管可用：非超管残留的 platform 选择回退个人。
        if (state.currentId === PLATFORM_ID && !opts.isAdmin) {
          state.currentId = null
          persist(null)
        } else if (
          state.currentId !== null &&
          state.currentId !== PLATFORM_ID &&
          !list.some((o) => o.id === state.currentId)
        ) {
          // 选中的组织已不在我的组织列表（如换了用户）→ 回退个人。
          state.currentId = null
          persist(null)
        }
      } finally {
        state.loaded = true
      }
    },
    /** 切换当前组织（null = 个人）。 */
    setCurrent(id) {
      const next = id === undefined ? null : id
      if (next === state.currentId) return
      state.currentId = next
      persist(next)
    },
    /** 退出登录时清空。 */
    reset() {
      state.orgs = [PERSONAL]
      state.currentId = null
      persist(null)
      state.loaded = false
    },
  }
}

// 供非 Vue 上下文（如 axios 拦截器）读取当前组织 id，避免循环依赖。
export function currentOrgIdRaw() {
  return state.currentId
}
