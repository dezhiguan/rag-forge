import { reactive, computed, readonly } from 'vue'
import { listOrgs } from '../api/org'

// 全局「当前组织」上下文（GitHub 式：个人 + 所属组织）。
// 个人组织在后端无实体（org_id 为 null），这里前端合成一个 personal 条目。
// 当前组织 id 持久化到 localStorage，并由 api/request.js 注入 X-Org-Id 请求头。
const STORAGE_KEY = 'ragforge.currentOrgId'
const PERSONAL = { id: null, name: '个人', slug: 'personal', myRole: 'OWNER', personal: true }

function readStored() {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
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
  const current = computed(() => state.orgs.find((o) => o.id === state.currentId) || PERSONAL)
  return {
    state: readonly(state),
    orgs: computed(() => state.orgs),
    current,
    currentOrgId: computed(() => state.currentId),
    isPersonal: computed(() => state.currentId === null),
    /** 拉取我的组织并合成个人条目；当前选中已失效则回退个人。 */
    async load() {
      try {
        const res = await listOrgs()
        const list = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
        state.orgs = [PERSONAL, ...list]
        if (state.currentId !== null && !list.some((o) => o.id === state.currentId)) {
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
