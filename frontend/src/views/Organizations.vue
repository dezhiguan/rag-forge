<template>
  <div class="orgs-page">
    <div class="toolbar">
      <button class="btn btn-primary" @click="openCreate">
        <span class="plus">＋</span> 创建组织
      </button>
      <button class="btn btn-secondary" :disabled="loading" @click="loadOrgs">刷新</button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="state-card">
      <div class="spinner" /> 加载中…
    </div>

    <!-- 空态 -->
    <div v-else-if="!orgs.length" class="empty-card">
      <div class="empty-icon">🏢</div>
      <div class="empty-title">还没有组织</div>
      <div class="empty-desc">创建组织后，可把知识库归属到组织，并邀请成员协作共享。</div>
      <button class="btn btn-primary" @click="openCreate"><span class="plus">＋</span> 创建组织</button>
    </div>

    <!-- 组织卡片网格 -->
    <div v-else class="org-grid">
      <article v-for="org in orgs" :key="org.id" class="org-card">
        <div class="org-head">
          <div class="org-avatar">{{ orgInitial(org.name) }}</div>
          <div class="org-meta">
            <div class="org-name" :title="org.name">{{ org.name }}</div>
            <div class="org-slug">@{{ org.slug }}</div>
          </div>
          <span class="role-tag" :class="'role-' + (org.myRole || '').toLowerCase()">
            {{ roleLabel(org.myRole) }}
          </span>
        </div>
        <div class="org-foot">
          <button
            v-if="isOrgAdmin(org)"
            class="btn btn-secondary btn-sm"
            @click="openMembers(org)"
          >
            管理成员
          </button>
          <span v-else class="foot-hint">成员身份 · 只读</span>
        </div>
      </article>
    </div>

    <!-- 创建组织 -->
    <Teleport to="body">
      <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
        <div class="modal">
          <div class="modal-header">
            <h3 class="modal-title">创建组织</h3>
            <button class="modal-close" @click="showCreate = false">✕</button>
          </div>
          <div class="modal-body">
            <label class="field">
              <span class="field-label">组织名称 <i>*</i></span>
              <input v-model="createForm.name" type="text" placeholder="例如：广州日不落科技有限公司" />
            </label>
            <label class="field">
              <span class="field-label">标识 slug <i>*</i></span>
              <input v-model="createForm.slug" type="text" placeholder="例如：rblk" />
              <span class="field-hint">小写字母 / 数字 / 连字符，组织的唯一短标识，创建后用于归属知识库</span>
            </label>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="showCreate = false">取消</button>
            <button class="btn btn-primary" :disabled="submitting" @click="onCreate">
              {{ submitting ? '创建中…' : '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 成员管理 -->
    <Teleport to="body">
      <div v-if="showMembers" class="modal-mask" @click.self="showMembers = false">
        <div class="modal modal-wide">
          <div class="modal-header">
            <div>
              <h3 class="modal-title">成员管理</h3>
              <div class="modal-sub">{{ activeOrg?.name }} · @{{ activeOrg?.slug }}</div>
            </div>
            <button class="modal-close" @click="showMembers = false">✕</button>
          </div>

          <div class="modal-body">
            <div class="add-member">
              <div class="member-search">
                <input
                  v-model="memberQuery"
                  type="text"
                  placeholder="搜索用户名 / 邮箱 / 昵称"
                  class="mini-input"
                  data-test="member-search-input"
                  @input="onMemberSearchInput"
                  @focus="candidatesOpen = candidates.length > 0"
                  @blur="onMemberSearchBlur"
                />
                <div v-if="candidatesOpen && candidates.length" class="candidate-list">
                  <div
                    v-for="c in candidates"
                    :key="c.userId"
                    class="candidate-row"
                    :class="{ disabled: c.alreadyMember }"
                    data-test="member-candidate"
                    @mousedown.prevent="pickCandidate(c)"
                  >
                    <span class="candidate-name">{{ c.displayName }}</span>
                    <span class="candidate-meta">{{ c.email || c.maskedPhone || ('用户 ' + c.userId) }}</span>
                    <span v-if="c.alreadyMember" class="candidate-tag">已是成员</span>
                  </div>
                </div>
                <div
                  v-else-if="candidatesOpen && memberQuery.trim().length >= 2 && !searching"
                  class="candidate-empty"
                >
                  无匹配用户（仅能搜到登录过本平台的用户）
                </div>
              </div>
              <select v-model="memberForm.role" class="mini-input">
                <option value="MEMBER">成员</option>
                <option value="ADMIN">管理员</option>
                <option v-if="activeOrgIsOwner" value="OWNER">所有者</option>
              </select>
              <button
                class="btn btn-primary btn-sm"
                :disabled="submitting || !memberForm.userId"
                data-test="member-add-btn"
                @click="onAddMember"
              >
                添加成员
              </button>
            </div>

            <div class="add-member invite-by-phone">
              <input
                v-model="invitePhone"
                type="tel"
                placeholder="按手机号邀请（对方需接受）"
                class="mini-input"
              />
              <select v-model="inviteRole" class="mini-input">
                <option value="MEMBER">成员</option>
                <option value="ADMIN">管理员</option>
              </select>
              <button
                class="btn btn-secondary btn-sm"
                :disabled="submitting || !invitePhone.trim()"
                @click="onInviteByPhone"
              >
                发送邀请
              </button>
            </div>

            <div class="member-list">
              <div v-for="m in members" :key="m.userId" class="member-row">
                <div class="member-avatar">{{ (m.displayName || ('U' + m.userId)).trim().slice(0, 2) }}</div>
                <div class="member-id">
                  <span class="member-name">{{ m.displayName || ('用户 ' + m.userId) }}</span>
                  <span v-if="m.email" class="member-email">{{ m.email }}</span>
                </div>
                <select
                  :value="m.role"
                  class="mini-input role-select"
                  @change="onChangeRole(m, $event.target.value)"
                >
                  <option value="MEMBER">成员</option>
                  <option value="ADMIN">管理员</option>
                  <option v-if="activeOrgIsOwner" value="OWNER">所有者</option>
                </select>
                <button class="icon-btn danger" title="移除成员" @click="onRemoveMember(m)">移除</button>
              </div>
              <div v-if="!members.length" class="member-empty">暂无成员</div>
            </div>
          </div>

          <div class="modal-footer">
            <button class="btn btn-secondary" @click="showMembers = false">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  listOrgs,
  createOrg,
  listMembers as listMembersApi,
  addMember as addMemberApi,
  updateMember as updateMemberApi,
  removeMember as removeMemberApi,
  searchMemberCandidates as searchMemberCandidatesApi,
  inviteByPhone as inviteByPhoneApi,
} from '../api/org'
import { useToast } from '../composables/useToast'
import { confirm as confirmDialog } from '../composables/useConfirm'

const toast = useToast()

const orgs = ref([])
const loading = ref(false)
const submitting = ref(false)

const showCreate = ref(false)
const createForm = ref({ name: '', slug: '' })

const showMembers = ref(false)
const activeOrg = ref(null)
const members = ref([])
const memberForm = ref({ userId: null, role: 'MEMBER' })
const invitePhone = ref('')
const inviteRole = ref('MEMBER')

// 成员搜索：输入关键词 → 防抖查后端候选 → 选中后才落 userId
const memberQuery = ref('')
const candidates = ref([])
const candidatesOpen = ref(false)
const searching = ref(false)
let searchTimer = null

const activeOrgIsOwner = computed(() => activeOrg.value?.myRole === 'OWNER')

function roleLabel(role) {
  return { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }[role] || role || '-'
}
function isOrgAdmin(org) {
  return org?.myRole === 'OWNER' || org?.myRole === 'ADMIN'
}
function orgInitial(name) {
  return (name || '组').trim().slice(0, 1).toUpperCase()
}

async function loadOrgs() {
  loading.value = true
  try {
    const res = await listOrgs()
    orgs.value = res?.data || res || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.value = { name: '', slug: '' }
  showCreate.value = true
}

async function onCreate() {
  if (!createForm.value.name?.trim() || !createForm.value.slug?.trim()) {
    toast.warning('请填写组织名称和标识')
    return
  }
  submitting.value = true
  try {
    await createOrg({ name: createForm.value.name.trim(), slug: createForm.value.slug.trim() })
    showCreate.value = false
    toast.success('组织已创建')
    await loadOrgs()
  } finally {
    submitting.value = false
  }
}

async function openMembers(org) {
  activeOrg.value = org
  resetMemberSearch()
  memberForm.value = { userId: null, role: 'MEMBER' }
  showMembers.value = true
  await loadMembers()
}

function resetMemberSearch() {
  memberQuery.value = ''
  candidates.value = []
  candidatesOpen.value = false
  searching.value = false
  if (searchTimer) clearTimeout(searchTimer)
}

function onMemberSearchInput() {
  // 关键词一变,之前选中的人即作废,必须重新从候选里选,避免 userId 与输入框对不上
  memberForm.value.userId = null
  const q = memberQuery.value.trim()
  if (searchTimer) clearTimeout(searchTimer)
  if (q.length < 2) {
    candidates.value = []
    candidatesOpen.value = false
    return
  }
  searchTimer = setTimeout(() => runMemberSearch(q), 250)
}

async function runMemberSearch(q) {
  if (!activeOrg.value) return
  searching.value = true
  candidatesOpen.value = true
  try {
    const res = await searchMemberCandidatesApi(activeOrg.value.id, q)
    candidates.value = res?.data || res || []
  } catch (e) {
    candidates.value = []
  } finally {
    searching.value = false
  }
}

function pickCandidate(c) {
  if (c.alreadyMember) {
    toast.warning('该用户已是组织成员')
    return
  }
  memberForm.value.userId = c.userId
  memberQuery.value = c.displayName || `用户 ${c.userId}`
  candidatesOpen.value = false
}

function onMemberSearchBlur() {
  // 延迟关闭,让候选项的 mousedown 先触发选中
  setTimeout(() => {
    candidatesOpen.value = false
  }, 150)
}

async function loadMembers() {
  const res = await listMembersApi(activeOrg.value.id)
  members.value = res?.data || res || []
}

async function onAddMember() {
  if (!memberForm.value.userId) {
    toast.warning('请先搜索并选择一个用户')
    return
  }
  submitting.value = true
  try {
    await addMemberApi(activeOrg.value.id, {
      userId: Number(memberForm.value.userId),
      role: memberForm.value.role,
    })
    const role = memberForm.value.role
    resetMemberSearch()
    memberForm.value = { userId: null, role }
    await loadMembers()
  } finally {
    submitting.value = false
  }
}

async function onInviteByPhone() {
  const phone = invitePhone.value.trim()
  if (!phone) return
  submitting.value = true
  try {
    const res = await inviteByPhoneApi(activeOrg.value.id, phone, inviteRole.value)
    const d = res?.data || {}
    invitePhone.value = ''
    if (d.registered) {
      toast.success(`已发送站内邀请（${d.maskedPhone || phone}），待对方接受`)
    } else {
      toast.success('该手机号未注册，邀请已暂存；对方注册后可在通知中接受')
    }
  } catch (e) {
    toast.error(e?.message || '邀请失败')
  } finally {
    submitting.value = false
  }
}

async function onChangeRole(member, role) {
  if (role === member.role) return
  try {
    await updateMemberApi(activeOrg.value.id, member.userId, { role })
    await loadMembers()
  } catch (e) {
    await loadMembers()
  }
}

async function onRemoveMember(member) {
  const ok = await confirmDialog({
    title: '移除成员',
    message: `确认把 ${member.displayName || '用户 ' + member.userId} 移出组织？`,
    confirmText: '移除',
  })
  if (!ok) return
  await removeMemberApi(activeOrg.value.id, member.userId)
  await loadMembers()
}

const route = useRoute()
const router = useRouter()

onMounted(async () => {
  await loadOrgs()
  // 从驾驶舱「升级到团队组织」深链进入：自动打开创建组织弹窗
  if (route.query.create) {
    openCreate()
    router.replace({ path: route.path })
  }
})
</script>

<style scoped>
.orgs-page { padding: 24px 28px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 22px; }
.plus { font-weight: 700; margin-right: 2px; }
.btn-sm { height: 32px; padding: 0 14px; font-size: 13px; }

/* 加载 / 空态 */
.state-card {
  display: flex; align-items: center; justify-content: center; gap: 10px;
  padding: 64px; color: var(--text-muted);
  background: #fff; border: 1px solid var(--border); border-radius: var(--radius-lg);
}
.spinner {
  width: 18px; height: 18px; border-radius: 50%;
  border: 2px solid var(--border); border-top-color: var(--primary);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.empty-card {
  display: flex; flex-direction: column; align-items: center; text-align: center;
  padding: 72px 24px; gap: 12px;
  background: #fff; border: 1px solid var(--border); border-radius: var(--radius-lg);
}
.empty-icon {
  width: 72px; height: 72px; border-radius: var(--radius-full);
  display: grid; place-items: center; font-size: 32px;
  background: var(--primary-soft); border: 1px solid var(--primary-border);
}
.empty-title { font-size: 17px; font-weight: 700; color: var(--text); }
.empty-desc { font-size: 13px; color: var(--text-muted); max-width: 380px; line-height: 1.7; margin-bottom: 6px; }

/* 组织卡片 */
.org-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px;
}
.org-card {
  background: #fff; border: 1px solid var(--border); border-radius: var(--radius-lg);
  padding: 18px; transition: box-shadow .18s, transform .18s, border-color .18s;
}
.org-card:hover {
  box-shadow: 0 8px 24px rgba(2, 6, 23, .08);
  border-color: var(--primary-border);
  transform: translateY(-2px);
}
.org-head { display: flex; align-items: center; gap: 12px; }
.org-avatar {
  width: 46px; height: 46px; border-radius: var(--radius-md); flex-shrink: 0;
  display: grid; place-items: center; color: #fff; font-weight: 700; font-size: 19px;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
}
.org-meta { flex: 1; min-width: 0; }
.org-name { font-size: 15px; font-weight: 700; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.org-slug { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
.org-foot { margin-top: 16px; padding-top: 14px; border-top: 1px dashed var(--border); display: flex; justify-content: flex-end; align-items: center; min-height: 32px; }
.foot-hint { font-size: 12px; color: var(--text-muted); }

/* 角色标签 */
.role-tag { flex-shrink: 0; font-size: 11px; font-weight: 700; border-radius: var(--radius-full); padding: 2px 10px; }
.role-owner { background: #fef3c7; color: #92400e; }
.role-admin { background: #dbeafe; color: #1e40af; }
.role-member { background: #f1f5f9; color: #475569; }

/* 弹窗 */
.modal-mask {
  position: fixed; inset: 0; z-index: 1000; padding: 24px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(15, 23, 42, .45); backdrop-filter: blur(2px);
}
.modal {
  width: min(460px, 94vw); background: #fff; border-radius: var(--radius-lg);
  box-shadow: 0 24px 70px rgba(2, 6, 23, .28); overflow: hidden;
  animation: pop .16s ease-out;
}
.modal-wide { width: min(580px, 94vw); }
@keyframes pop { from { opacity: 0; transform: translateY(8px) scale(.98); } to { opacity: 1; transform: none; } }
.modal-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  padding: 18px 22px; border-bottom: 1px solid var(--border);
}
.modal-title { font-size: 16px; font-weight: 700; color: var(--text); }
.modal-sub { font-size: 12px; color: var(--text-muted); margin-top: 3px; }
.modal-close { border: none; background: transparent; color: var(--text-muted); font-size: 15px; cursor: pointer; padding: 2px 6px; border-radius: var(--radius-sm); }
.modal-close:hover { background: var(--light); color: var(--text); }
.modal-body { padding: 22px; display: flex; flex-direction: column; gap: 18px; }
.modal-footer {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 14px 22px; border-top: 1px solid var(--border); background: #fafbfc;
}

/* 表单 */
.field { display: flex; flex-direction: column; gap: 7px; }
.field-label { font-size: 13px; font-weight: 600; color: var(--text); }
.field-label i { color: #ef4444; font-style: normal; }
.field input {
  height: 42px; padding: 0 13px; width: 100%;
  border: 1px solid var(--border); border-radius: var(--radius-md);
  font-size: 14px; color: var(--text); background: #fff; transition: border-color .15s, box-shadow .15s;
}
.field input::placeholder { color: #94a3b8; }
.field input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.field-hint { font-size: 12px; color: var(--text-muted); line-height: 1.6; }

/* 成员管理 */
.add-member {
  display: flex; gap: 8px; align-items: center;
  padding: 12px; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md);
}
.mini-input {
  height: 36px; padding: 0 10px; border: 1px solid var(--border);
  border-radius: var(--radius-sm); font-size: 13px; color: var(--text); background: #fff;
}
.mini-input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.member-search { flex: 1; position: relative; }
.member-search .mini-input { width: 100%; }
.candidate-list {
  position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 20;
  background: #fff; border: 1px solid var(--border); border-radius: var(--radius-sm);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12); max-height: 240px; overflow-y: auto;
}
.candidate-row {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; cursor: pointer; font-size: 13px;
}
.candidate-row:hover { background: var(--light); }
.candidate-row.disabled { cursor: not-allowed; opacity: 0.55; }
.candidate-name { font-weight: 600; color: var(--text); }
.candidate-meta { color: var(--text-muted); font-size: 12px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.candidate-tag { font-size: 11px; color: var(--text-muted); border: 1px solid var(--border); border-radius: var(--radius-full); padding: 1px 8px; }
.candidate-empty {
  position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 20;
  background: #fff; border: 1px solid var(--border); border-radius: var(--radius-sm);
  padding: 12px; font-size: 12px; color: var(--text-muted); text-align: center;
}
.member-list { display: flex; flex-direction: column; }
.member-row {
  display: flex; align-items: center; gap: 12px;
  padding: 11px 4px; border-bottom: 1px solid var(--border);
}
.member-row:last-child { border-bottom: none; }
.member-avatar {
  width: 34px; height: 34px; border-radius: var(--radius-full); flex-shrink: 0;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; color: #475569; background: var(--light);
}
.member-id { flex: 1; display: flex; flex-direction: column; gap: 2px; font-size: 14px; color: var(--text); }
.member-name { font-weight: 600; }
.member-email { font-size: 12px; color: var(--text-muted); }
.role-select { width: 96px; }
.icon-btn {
  border: 1px solid var(--border); background: #fff; border-radius: var(--radius-sm);
  height: 30px; padding: 0 12px; font-size: 12px; cursor: pointer; color: var(--text-muted);
}
.icon-btn.danger:hover { border-color: #fecaca; background: #fef2f2; color: #dc2626; }
.member-empty { padding: 28px; text-align: center; color: var(--text-muted); font-size: 13px; }
</style>
