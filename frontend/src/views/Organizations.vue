<template>
  <div class="page-body org-mgmt">
    <!-- 引导态：个人组织 / 平台 / 未选团队组织 -->
    <div v-if="!isTeamOrg" class="org-gate">
      <div class="gate-card">
        <div class="gate-ico">🏢</div>
        <h2 class="gate-title">{{ isPersonal ? '当前是「个人组织」' : '选择一个组织来管理' }}</h2>
        <p class="gate-desc">
          个人组织无需管理成员；创建团队组织后可邀请成员、共享组织知识库、按组织聚合指标。
        </p>
        <button class="btn btn-primary" @click="openCreate"><span class="plus">＋</span> 创建组织</button>
      </div>

      <div v-if="manageableOrgs.length" class="gate-list">
        <div class="gate-list-label">切换到你所属的组织进行管理：</div>
        <div v-for="o in manageableOrgs" :key="o.id" class="gate-org" @click="switchTo(o.id)">
          <span class="org-ava sm" :style="{ background: avatarColor(o) }">{{ initial(o.name) }}</span>
          <div class="go-meta">
            <div class="go-name">{{ o.name }}</div>
            <div class="go-sub">@{{ o.slug }} · {{ roleLabel(o.myRole) }}</div>
          </div>
          <span class="go-arrow">管理 →</span>
        </div>
      </div>
    </div>

    <template v-else>
      <!-- 组织头 -->
      <div class="org-head">
        <span class="org-ava" :style="{ background: avatarColor(org) }">{{ initial(org.name) }}</span>
        <div class="org-head-meta">
          <div class="org-head-name">
            {{ org.name }} <span class="type-badge tb-team">TEAM</span>
          </div>
          <div class="org-head-sub">
            <span>slug <code>{{ org.slug }}</code></span>
            <span>org_id <code>{{ org.id }}</code></span>
            <span v-if="org.createdAt">创建于 {{ fmtDate(org.createdAt) }}</span>
            <span>你的角色 <b class="role-strong">{{ org.myRole }}</b></span>
          </div>
        </div>
        <button v-if="canManage" class="btn-primary head-invite" @click="openInvite">+ 邀请成员</button>
      </div>

      <div class="layout">
        <!-- 左导航 -->
        <div class="side-nav">
          <div
            v-for="n in navItems"
            :key="n.key"
            class="nav-item"
            :class="{ active: tab === n.key, danger: n.danger }"
            @click="tab = n.key"
          >
            <span class="ico">{{ n.ico }}</span>{{ n.label }}
            <span v-if="n.count != null" class="nav-count" :class="{ warn: n.warn }">{{ n.count }}</span>
            <span v-if="n.wip" class="wip-dot" title="待开发">·</span>
          </div>
        </div>

        <!-- 右内容 -->
        <div class="content">
          <!-- 通用 -->
          <section v-show="tab === 'general'" class="panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">通用设置</div>
                <div class="panel-desc">仅 OWNER / ADMIN 可修改</div>
              </div>
            </div>
            <div class="panel-body">
              <div class="form-row">
                <span class="form-label">组织名称</span>
                <input class="form-input" v-model="generalForm.name" :disabled="!canManage" />
              </div>
              <div class="form-row">
                <span class="form-label">slug（短标识）</span>
                <input class="form-input" v-model="generalForm.slug" :disabled="!canManage" />
              </div>
              <div class="form-row">
                <span class="form-label">组织 ID</span>
                <span class="form-static"><code>{{ org.id }}</code> · 不可修改</span>
              </div>
              <div class="form-row">
                <span class="form-label">类型</span>
                <span class="form-static"><span class="type-badge tb-team">TEAM</span> · 团队组织</span>
              </div>
              <div class="form-row">
                <span class="form-label">创建时间</span>
                <span class="form-static">{{ org.createdAt ? fmtDate(org.createdAt) : '—' }}</span>
              </div>
              <div class="form-actions">
                <button
                  class="btn-primary"
                  :disabled="!canManage || !generalDirty || savingGeneral"
                  @click="onSaveGeneral"
                >{{ savingGeneral ? '保存中…' : '保存修改' }}</button>
                <span v-if="!canManage" class="wip-note">仅 OWNER / ADMIN 可修改</span>
              </div>
            </div>
          </section>

          <!-- 成员 -->
          <section v-show="tab === 'members'">
            <div class="panel">
              <div class="panel-head">
                <div>
                  <div class="panel-title">组织成员</div>
                  <div class="panel-desc">支持搜索 / 改角色 / 移除</div>
                </div>
                <button v-if="canManage" class="btn-primary btn-sm" @click="openInvite">+ 邀请成员</button>
              </div>
              <div class="member-tools">
                <div class="search">
                  <span class="si">🔍</span>
                  <input v-model="memberSearch" placeholder="搜索用户名 / 显示名 / 邮箱" />
                </div>
                <select v-model="roleFilter" class="role-filter">
                  <option value="">全部角色</option>
                  <option value="OWNER">OWNER</option>
                  <option value="ADMIN">ADMIN</option>
                  <option value="MEMBER">MEMBER</option>
                </select>
              </div>

              <div class="m-list">
                <div v-for="m in filteredMembers" :key="m.userId" class="m-row">
                  <span class="m-ava" :style="{ background: avatarColor({ id: m.userId, name: m.displayName }) }">
                    {{ initial(m.displayName || ('U' + m.userId)) }}
                  </span>
                  <div class="m-meta">
                    <div class="m-name">
                      {{ m.displayName || ('用户 ' + m.userId) }}
                      <span v-if="m.userId === myUserId" class="you-tag">你</span>
                    </div>
                    <div class="m-sub">{{ m.email || ('用户 ID ' + m.userId) }}</div>
                  </div>
                  <div class="m-actions">
                    <span v-if="m.role === 'OWNER'" class="role-badge role-owner">OWNER</span>
                    <template v-else-if="canManage && m.userId !== myUserId">
                      <select
                        class="role-select"
                        :value="m.role"
                        @change="onChangeRole(m, $event.target.value)"
                      >
                        <option value="ADMIN">ADMIN</option>
                        <option value="MEMBER">MEMBER</option>
                      </select>
                      <span class="link-danger" @click="onRemoveMember(m)">移除</span>
                    </template>
                    <span v-else class="role-badge" :class="roleBadgeClass(m.role)">{{ m.role }}</span>
                  </div>
                </div>
                <div v-if="!filteredMembers.length" class="empty">没有匹配的成员</div>
              </div>
              <div class="list-foot">
                <span>共 {{ members.length }} 人{{ filteredMembers.length !== members.length ? `（筛出 ${filteredMembers.length}）` : '' }}</span>
              </div>
            </div>
            <div class="hint-box">
              🔒 <b>权限：</b>仅 <b>OWNER / ADMIN</b> 能改角色、移除成员；<b>OWNER 行不可被操作</b>；唯一 OWNER 不能移除自己（需先转移所有权）。所有写操作服务端强校验，前端隐藏≠真授权。
            </div>
          </section>

          <!-- 邀请 -->
          <section v-show="tab === 'invites'">
            <div class="panel">
              <div class="panel-head">
                <div>
                  <div class="panel-title">待处理邀请</div>
                  <div class="panel-desc">PENDING 不计入正式成员，对方接受后才写入组织</div>
                </div>
                <button v-if="canManage" class="btn-primary btn-sm" @click="openInvite">+ 邀请成员</button>
              </div>
              <div class="m-list">
                <div v-for="inv in invites" :key="inv.id" class="m-row">
                  <span class="m-ava" style="background: #94a3b8">✉</span>
                  <div class="m-meta">
                    <div class="m-name">
                      {{ inv.maskedPhone || '邀请' }} <span class="role-badge role-pending">PENDING</span>
                    </div>
                    <div class="m-sub">角色 {{ inv.role }} · {{ fmtDate(inv.createdAt) }}</div>
                  </div>
                  <div class="m-actions">
                    <span v-if="canManage" class="link-danger" @click="onRevoke(inv)">撤销</span>
                  </div>
                </div>
                <div v-if="!invites.length" class="empty">暂无待处理邀请</div>
              </div>
            </div>
            <div class="hint-box">
              📨 <b>邀请方式：</b>按<b>手机号</b>经网关精确解析；已注册→站内通知接受；未注册→短信拉新、注册后自动关联。解析仅返回脱敏号，不提供用户目录浏览。
            </div>
          </section>

          <!-- 知识库归属 -->
          <section v-show="tab === 'kb'">
            <div class="panel">
              <div class="panel-head">
                <div>
                  <div class="panel-title">组织知识库</div>
                  <div class="panel-desc">org_id={{ org.id }} 名下的库（visibility 决定成员可见范围）</div>
                </div>
              </div>
              <div class="m-list">
                <div v-for="kb in orgKbs" :key="kb.id" class="m-row">
                  <span class="m-ava" :style="{ background: avatarColor({ id: kb.id, name: kb.name }) }">
                    {{ initial(kb.name) }}
                  </span>
                  <div class="m-meta">
                    <div class="m-name">{{ kb.name }}</div>
                    <div class="m-sub">
                      visibility: {{ kb.visibility }} · {{ kb.docCount ?? 0 }} 文档 ·
                      {{ kb.visibility === 'ORG' ? '全体成员可读' : '仅 OWNER/ADMIN 可读' }}
                    </div>
                  </div>
                  <span class="role-badge" :class="kb.visibility === 'ORG' ? 'role-admin' : 'role-member'">
                    {{ kb.visibility }}
                  </span>
                </div>
                <div v-if="!orgKbs.length" class="empty">该组织名下暂无知识库</div>
              </div>
            </div>
            <div class="hint-box">
              📚 个人库转组织 / 组织库转回个人属「所有权转移」（改 org_id），在知识库页操作；删除库走库自己的危险操作。
            </div>
          </section>

          <!-- 套餐与升级（待开发） -->
          <section v-show="tab === 'plan'" class="panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">套餐与升级 <i class="wip-badge">待开发</i></div>
                <div class="panel-desc">类型由套餐决定；升级显式触发，组织 ID 不变、数据零迁移</div>
              </div>
            </div>
            <div class="panel-body">
              <div class="plan-now">
                <span class="pn-ico">✅</span>
                <div class="pn-meta">
                  <div class="pn-t">当前：TEAM（团队组织）</div>
                  <div class="pn-s">{{ members.length }} 名成员 · 可邀请成员、共享组织知识库</div>
                </div>
                <button class="btn" disabled>管理套餐</button>
              </div>
              <div class="state-flow">
                <span class="sf-node">INDIVIDUAL 个人组织</span>
                <span class="sf-arrow">──显式升档──▶</span>
                <span class="sf-node cur">TEAM 团队组织</span>
                <span class="sf-note">（你在这里）</span>
              </div>
              <div class="hint-box">
                ⬆️ <b>状态机：</b>INDIVIDUAL →(显式升档)→ TEAM；组织实体不变、零数据迁移。套餐/计费体系<b>后端待开发</b>，当前所有组织默认 TEAM。
              </div>
            </div>
          </section>

          <!-- 危险区 -->
          <section v-show="tab === 'danger'">
            <div class="danger-zone">
              <div class="dz-head">
                <div class="dz-title">危险区</div>
                <div class="dz-desc">高危操作，仅 OWNER 可执行，需二次确认</div>
              </div>
              <div class="dz-row">
                <div class="dz-info">
                  <div class="dz-r-t">转移所有权（OWNER）</div>
                  <div class="dz-r-s">将 OWNER 移交给另一名成员，自己降为 ADMIN</div>
                </div>
                <div class="dz-action">
                  <select v-model="transferTarget" class="role-filter" :disabled="!isOwner || !otherMembers.length">
                    <option :value="null" disabled>选择成员</option>
                    <option v-for="m in otherMembers" :key="m.userId" :value="m.userId">
                      {{ m.displayName || ('用户 ' + m.userId) }}
                    </option>
                  </select>
                  <button class="btn-danger" :disabled="!isOwner || !transferTarget" @click="onTransferOwner">转移</button>
                </div>
              </div>
              <div class="dz-row">
                <div class="dz-info">
                  <div class="dz-r-t">删除组织</div>
                  <div class="dz-r-s">
                    需先清空组织名下知识库；删除后不可恢复
                    <span v-if="orgKbs.length" class="dz-warn">· 当前还有 {{ orgKbs.length }} 个组织库</span>
                  </div>
                </div>
                <button class="btn-danger" :disabled="!isOwner || orgKbs.length > 0" @click="onDeleteOrg">删除组织</button>
              </div>
            </div>
            <div class="hint-box">
              ⚠️ 仅 OWNER 可执行，转移 / 删除均需二次确认。删除组织前请先把名下知识库转出或删除。
            </div>
          </section>
        </div>
      </div>
    </template>

    <!-- 创建组织 -->
    <Teleport to="body">
      <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
        <div class="modal">
          <div class="modal-header">
            <h3 class="modal-title">创建组织</h3>
            <button class="modal-close" @click="showCreate = false">✕</button>
          </div>
          <div class="modal-body">
            <label class="field"><span>组织名称 *</span>
              <input v-model="createForm.name" type="text" placeholder="例如：广州日不落科技有限公司" />
            </label>
            <label class="field"><span>slug（短标识）*</span>
              <input v-model="createForm.slug" type="text" placeholder="小写字母/数字/连字符，如 rblk" />
            </label>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="showCreate = false">取消</button>
            <button class="btn btn-primary" :disabled="submitting" @click="onCreate">创建</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 邀请成员 -->
    <Teleport to="body">
      <div v-if="showInvite" class="modal-mask" @click.self="showInvite = false">
        <div class="modal">
          <div class="modal-header">
            <h3 class="modal-title">邀请成员加入「{{ org.name }}」</h3>
            <button class="modal-close" @click="showInvite = false">✕</button>
          </div>
          <div class="modal-body">
            <label class="field"><span>手机号</span>
              <input v-model="inviteForm.phone" type="text" placeholder="请输入完整手机号，如 13800000000" />
            </label>
            <label class="field"><span>分配角色</span>
              <select v-model="inviteForm.role">
                <option value="MEMBER">MEMBER（成员）</option>
                <option value="ADMIN">ADMIN（管理员）</option>
              </select>
            </label>
            <div class="pii-note">
              🔒 经网关精确解析单个手机号（限频、返回脱敏号）：已注册→站内通知接受；未注册→短信邀请，注册后自动关联。不返回明文、不可批量、不提供用户列表。
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="showInvite = false">取消</button>
            <button class="btn btn-primary" :disabled="inviting || !inviteForm.phone" @click="onSendInvite">
              发送邀请
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import {
  createOrg,
  updateOrg,
  transferOwner,
  deleteOrg,
  listMembers as listMembersApi,
  updateMember as updateMemberApi,
  removeMember as removeMemberApi,
  inviteByPhone,
  listOrgInvitations,
  revokeInvitation,
} from '../api/org'
import { listKb } from '../api/kb'
import { useOrg } from '../composables/useOrg'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'
import { confirm as confirmDialog } from '../composables/useConfirm'

const toast = useToast()
const { state: authState } = useAuth()
const { current, orgs, isPersonal, isPlatform, load: loadOrgs, setCurrent } = useOrg()

const org = computed(() => current.value)
const isTeamOrg = computed(() => !isPersonal.value && !isPlatform.value && org.value?.id != null)
const canManage = computed(() => org.value?.myRole === 'OWNER' || org.value?.myRole === 'ADMIN')
const isOwner = computed(() => org.value?.myRole === 'OWNER')
const myUserId = computed(() => authState.me?.userId ?? null)
const manageableOrgs = computed(() => orgs.value.filter((o) => o.id != null))

const tab = ref('general')
const members = ref([])
const invites = ref([])
const orgKbs = ref([])
const memberSearch = ref('')
const roleFilter = ref('')
const generalForm = ref({ name: '', slug: '' })
const savingGeneral = ref(false)
const transferTarget = ref(null)
const otherMembers = computed(() => members.value.filter((m) => m.userId !== myUserId.value))

const navItems = computed(() => [
  { key: 'general', ico: '⚙️', label: '通用' },
  { key: 'members', ico: '👥', label: '成员', count: members.value.length },
  { key: 'invites', ico: '✉️', label: '邀请', count: invites.value.length, warn: invites.value.length > 0 },
  { key: 'kb', ico: '📚', label: '知识库归属' },
  { key: 'plan', ico: '⬆️', label: '套餐与升级', wip: true },
  { key: 'danger', ico: '⚠️', label: '危险区', danger: true },
])

const filteredMembers = computed(() => {
  const q = memberSearch.value.trim().toLowerCase()
  return members.value.filter((m) => {
    const hitText =
      !q ||
      (m.displayName || '').toLowerCase().includes(q) ||
      (m.email || '').toLowerCase().includes(q) ||
      String(m.userId).includes(q)
    return hitText && (!roleFilter.value || m.role === roleFilter.value)
  })
})

async function loadAll() {
  if (!isTeamOrg.value) return
  const id = org.value.id
  await Promise.all([loadMembers(id), loadInvites(id), loadOrgKbs(id)])
}
async function loadMembers(id) {
  try {
    const res = await listMembersApi(id)
    members.value = Array.isArray(res?.data) ? res.data : []
  } catch {
    members.value = []
  }
}
async function loadInvites(id) {
  if (!canManage.value) {
    invites.value = []
    return
  }
  try {
    const res = await listOrgInvitations(id)
    invites.value = Array.isArray(res?.data) ? res.data : []
  } catch {
    invites.value = []
  }
}
async function loadOrgKbs(id) {
  try {
    const res = await listKb()
    const list = Array.isArray(res?.data) ? res.data : []
    orgKbs.value = list.filter((kb) => kb.orgId === id)
  } catch {
    orgKbs.value = []
  }
}

watch(
  () => org.value?.id,
  () => {
    tab.value = 'general'
    generalForm.value = { name: org.value?.name || '', slug: org.value?.slug || '' }
    transferTarget.value = null
    loadAll()
  },
  { immediate: true },
)

const generalDirty = computed(
  () =>
    (generalForm.value.name || '') !== (org.value?.name || '') ||
    (generalForm.value.slug || '') !== (org.value?.slug || ''),
)

async function onSaveGeneral() {
  if (!canManage.value || !generalDirty.value || savingGeneral.value) return
  savingGeneral.value = true
  try {
    await updateOrg(org.value.id, { name: generalForm.value.name.trim(), slug: generalForm.value.slug.trim() })
    await loadOrgs()
    toast.success('组织信息已保存')
  } catch (e) {
    /* 错误由全局拦截提示 */
  } finally {
    savingGeneral.value = false
  }
}

async function onTransferOwner() {
  if (!isOwner.value || !transferTarget.value) return
  const target = members.value.find((m) => m.userId === transferTarget.value)
  const ok = await confirmDialog({
    title: '转移所有权',
    message: `确认把 OWNER 移交给 ${target?.displayName || '用户 ' + transferTarget.value}？你将降为 ADMIN，此操作立即生效。`,
    confirmText: '确认转移',
  })
  if (!ok) return
  await transferOwner(org.value.id, transferTarget.value)
  await Promise.all([loadOrgs(), loadMembers(org.value.id)])
  transferTarget.value = null
  toast.success('所有权已转移')
}

async function onDeleteOrg() {
  if (!isOwner.value) return
  if (orgKbs.value.length > 0) {
    toast.error('请先把组织名下知识库转出或删除，再删除组织')
    return
  }
  const ok = await confirmDialog({
    title: '删除组织',
    message: `确认删除组织「${org.value.name}」？成员关系将一并清除，删除后不可恢复。`,
    confirmText: '删除组织',
  })
  if (!ok) return
  await deleteOrg(org.value.id)
  setCurrent(null)
  await loadOrgs()
  toast.success('组织已删除')
}

async function onChangeRole(member, role) {
  if (role === member.role) return
  try {
    await updateMemberApi(org.value.id, member.userId, { role })
    toast.success('角色已更新')
  } catch (e) {
    /* 错误由全局拦截提示 */
  }
  await loadMembers(org.value.id)
}

async function onRemoveMember(member) {
  const ok = await confirmDialog({
    title: '移除成员',
    message: `确认把 ${member.displayName || '用户 ' + member.userId} 移出组织？其个人库不受影响。`,
    confirmText: '移除',
  })
  if (!ok) return
  await removeMemberApi(org.value.id, member.userId)
  await loadMembers(org.value.id)
  toast.success('已移除')
}

async function onRevoke(inv) {
  const ok = await confirmDialog({
    title: '撤销邀请',
    message: `撤销对 ${inv.maskedPhone || '该手机号'} 的邀请？`,
    confirmText: '撤销',
  })
  if (!ok) return
  await revokeInvitation(org.value.id, inv.id)
  await loadInvites(org.value.id)
  toast.success('已撤销')
}

// 邀请弹窗
const showInvite = ref(false)
const inviting = ref(false)
const inviteForm = ref({ phone: '', role: 'MEMBER' })
function openInvite() {
  inviteForm.value = { phone: '', role: 'MEMBER' }
  showInvite.value = true
}
async function onSendInvite() {
  const phone = inviteForm.value.phone.trim()
  if (!/^1\d{10}$/.test(phone)) {
    toast.warning('请输入 11 位手机号')
    return
  }
  inviting.value = true
  try {
    const res = await inviteByPhone(org.value.id, phone, inviteForm.value.role)
    const data = res?.data || {}
    showInvite.value = false
    toast.success(data.registered ? '已发送站内邀请，待对方接受' : '已发送短信邀请，对方注册后自动关联')
    await loadInvites(org.value.id)
  } finally {
    inviting.value = false
  }
}

// 创建组织
const showCreate = ref(false)
const submitting = ref(false)
const createForm = ref({ name: '', slug: '' })
function openCreate() {
  createForm.value = { name: '', slug: '' }
  showCreate.value = true
}
async function onCreate() {
  if (!createForm.value.name.trim() || !createForm.value.slug.trim()) {
    toast.warning('请填写组织名称和 slug')
    return
  }
  submitting.value = true
  try {
    const res = await createOrg({ name: createForm.value.name.trim(), slug: createForm.value.slug.trim() })
    const newId = res?.data?.id
    showCreate.value = false
    toast.success('组织已创建')
    await loadOrgs()
    if (newId) setCurrent(newId)
  } finally {
    submitting.value = false
  }
}

function switchTo(id) {
  setCurrent(id)
}

// 工具
const PALETTE = ['#0f1f3d', '#2563eb', '#15803d', '#7c3aed', '#db2777', '#0ea5e9', '#f59e0b', '#65a30d']
function avatarColor(o) {
  const seed = o?.id != null ? Number(o.id) : (o?.name || '').length
  return PALETTE[Math.abs(seed || 0) % PALETTE.length]
}
function initial(name) {
  return (name || '?').trim().charAt(0).toUpperCase()
}
function roleLabel(role) {
  return { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }[role] || role || '-'
}
function roleBadgeClass(role) {
  return { OWNER: 'role-owner', ADMIN: 'role-admin', MEMBER: 'role-member' }[role] || 'role-member'
}
function fmtDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 10)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

onMounted(async () => {
  if (!orgs.value.some((o) => o.id != null)) await loadOrgs()
  await loadAll()
})
</script>

<style scoped>
.org-mgmt { max-width: 1080px; }

/* 引导态 */
.org-gate { display: flex; flex-direction: column; gap: 18px; }
.gate-card {
  background: var(--surface); border: 1px solid var(--border); border-radius: 16px;
  box-shadow: var(--shadow-sm); padding: 36px; text-align: center;
}
.gate-ico { font-size: 36px; }
.gate-title { font-size: 18px; font-weight: 700; color: var(--navy); margin: 12px 0 6px; }
.gate-desc { font-size: 13px; color: var(--text-muted); max-width: 460px; margin: 0 auto 18px; line-height: 1.7; }
.gate-list { background: var(--surface); border: 1px solid var(--border); border-radius: 14px; box-shadow: var(--shadow-sm); padding: 8px; }
.gate-list-label { font-size: 12.5px; color: var(--text-muted); padding: 8px 10px 4px; }
.gate-org { display: flex; align-items: center; gap: 12px; padding: 11px 12px; border-radius: 10px; cursor: pointer; }
.gate-org:hover { background: var(--light); }
.go-meta { flex: 1; min-width: 0; }
.go-name { font-size: 14px; font-weight: 600; color: var(--slate); }
.go-sub { font-size: 12px; color: var(--text-muted); }
.go-arrow { font-size: 12.5px; color: var(--primary); font-weight: 600; }

/* 组织头 */
.org-head {
  display: flex; align-items: center; gap: 14px; background: var(--surface);
  border: 1px solid var(--border); border-radius: 16px; padding: 18px 20px;
  box-shadow: var(--shadow-sm); margin-bottom: 18px;
}
.org-ava {
  width: 52px; height: 52px; border-radius: 13px; color: #fff; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center; font-size: 22px; font-weight: 700;
}
.org-ava.sm { width: 38px; height: 38px; border-radius: 10px; font-size: 16px; }
.org-head-meta { flex: 1; min-width: 0; }
.org-head-name { font-size: 19px; font-weight: 700; color: var(--navy); display: flex; align-items: center; gap: 9px; }
.org-head-sub { font-size: 12.5px; color: var(--text-muted); margin-top: 4px; display: flex; gap: 12px; flex-wrap: wrap; }
.org-head-sub code, .form-static code { background: #f1f5f9; border-radius: 5px; padding: 1px 6px; font-size: 11.5px; color: var(--slate); }
.role-strong { color: #92400e; }
.type-badge { font-size: 10.5px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.tb-team { background: #f0fdf4; color: #15803d; }
.head-invite { flex-shrink: 0; }

/* 布局 */
.layout { display: grid; grid-template-columns: 200px 1fr; gap: 18px; }
.side-nav {
  background: var(--surface); border: 1px solid var(--border); border-radius: 14px;
  padding: 8px; box-shadow: var(--shadow-sm); height: fit-content; position: sticky; top: 18px;
}
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 9px;
  font-size: 13.5px; color: var(--slate); cursor: pointer; font-weight: 500;
}
.nav-item:hover { background: var(--light); }
.nav-item.active { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.nav-item.danger { color: var(--red); }
.nav-item.danger.active { background: #fef2f2; color: var(--red); }
.nav-item .ico { width: 18px; text-align: center; }
.nav-count { margin-left: auto; font-size: 12px; color: var(--text-muted); }
.nav-count.warn { color: var(--amber); }
.wip-dot { color: var(--amber); font-weight: 900; margin-left: auto; }

/* 面板 */
.panel { background: var(--surface); border: 1px solid var(--border); border-radius: 14px; box-shadow: var(--shadow-sm); margin-bottom: 18px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.panel-title { font-size: 15px; font-weight: 700; color: var(--navy); }
.panel-desc { font-size: 12.5px; color: var(--text-muted); margin-top: 3px; }
.panel-body { padding: 18px 20px; }
.wip-badge { font-style: normal; font-size: 10px; font-weight: 700; color: var(--amber); background: #fffbeb; border: 1px solid #fde68a; border-radius: 999px; padding: 1px 7px; margin-left: 6px; }
.wip-badge.wip-danger { color: #b45309; }

/* 表单行 */
.form-row { display: flex; align-items: center; gap: 16px; padding: 12px 0; border-top: 1px dashed var(--border); }
.form-row:first-child { border-top: none; }
.form-label { width: 160px; font-size: 13px; color: var(--gray); font-weight: 600; flex-shrink: 0; }
.form-input { flex: 1; border: 1px solid var(--border); border-radius: 9px; padding: 9px 12px; font-size: 13px; max-width: 360px; background: #fff; color: var(--slate); }
.form-input:disabled { background: #f8fafc; color: var(--text-muted); }
.form-static { flex: 1; font-size: 13px; color: var(--slate); }
.form-actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
.wip-note { font-size: 12px; color: var(--text-muted); }

/* 组件内按钮统一样式（这页按钮只带 btn-primary，未带基础 .btn 类） */
.btn-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  border: none;
  border-radius: 9px;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(37, 99, 235, 0.28);
  transition: background 0.15s ease, box-shadow 0.15s ease, opacity 0.15s ease;
}
.btn-primary:hover:not(:disabled) { background: var(--primary-hover); }
.btn-primary:disabled { opacity: 0.55; cursor: not-allowed; box-shadow: none; }
.btn-primary.btn-sm { height: 30px; padding: 0 12px; font-size: 12px; border-radius: 8px; }

/* 成员/列表 */
.member-tools { display: flex; align-items: center; gap: 10px; padding: 14px 20px; border-bottom: 1px solid var(--border); }
.search { flex: 1; max-width: 320px; position: relative; }
.search input { width: 100%; border: 1px solid var(--border); border-radius: 9px; padding: 8px 12px 8px 32px; font-size: 13px; }
.search input:focus { outline: none; border-color: var(--primary); }
.search .si { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--text-muted); font-size: 13px; }
.role-filter { border: 1px solid var(--border); border-radius: 9px; padding: 8px 10px; font-size: 13px; color: var(--slate); }
.m-list { display: flex; flex-direction: column; }
.m-row { display: flex; align-items: center; gap: 12px; padding: 12px 20px; border-top: 1px solid var(--border); }
.m-row:first-child { border-top: none; }
.m-ava { width: 36px; height: 36px; border-radius: 10px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700; flex-shrink: 0; }
.m-meta { flex: 1; min-width: 0; }
.m-name { font-size: 13.5px; font-weight: 600; color: var(--slate); display: flex; align-items: center; gap: 8px; }
.m-sub { font-size: 11.5px; color: var(--text-muted); margin-top: 1px; }
.you-tag { font-size: 10.5px; font-weight: 700; padding: 1px 7px; border-radius: 999px; background: #f1f5f9; color: #475569; }
.role-badge { font-size: 10.5px; font-weight: 700; padding: 2px 9px; border-radius: 999px; }
.role-owner { background: #fef3c7; color: #92400e; }
.role-admin { background: #e0e7ff; color: #3730a3; }
.role-member { background: #f1f5f9; color: #475569; }
.role-pending { background: #fff7ed; color: #c2410c; }
.role-select { border: 1px solid var(--border); border-radius: 8px; padding: 5px 8px; font-size: 12px; color: var(--slate); }
.m-actions { display: flex; align-items: center; gap: 10px; min-width: 140px; justify-content: flex-end; }
.link-danger { color: var(--red); font-size: 12.5px; cursor: pointer; }
.link-danger:hover { text-decoration: underline; }
.list-foot { padding: 12px 20px; border-top: 1px solid var(--border); font-size: 12.5px; color: var(--text-muted); }
.empty { padding: 30px 0; text-align: center; color: var(--text-muted); font-size: 13px; }

.hint-box { font-size: 12px; color: var(--text-muted); line-height: 1.7; background: #fbfcfe; border: 1px dashed var(--border); border-radius: 10px; padding: 12px 14px; margin-bottom: 18px; }
.hint-box b { color: var(--slate); }

/* 套餐 */
.plan-now { display: flex; align-items: center; gap: 14px; padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: #f7faff; margin-bottom: 16px; }
.pn-ico { width: 40px; height: 40px; border-radius: 11px; background: var(--primary-soft); color: var(--primary); display: inline-flex; align-items: center; justify-content: center; font-size: 18px; }
.pn-meta { flex: 1; }
.pn-t { font-size: 14px; font-weight: 700; color: var(--slate); }
.pn-s { font-size: 12.5px; color: var(--text-muted); margin-top: 2px; }
.plan-now .btn { border: 1px solid var(--border); background: #fff; border-radius: 9px; padding: 7px 13px; font-size: 13px; color: var(--text-muted); cursor: not-allowed; }
.state-flow { display: flex; align-items: center; gap: 10px; font-size: 12.5px; color: var(--gray); margin: 12px 0; flex-wrap: wrap; }
.sf-node { border: 1px solid var(--border); border-radius: 8px; padding: 5px 11px; font-weight: 600; background: #fff; }
.sf-node.cur { border-color: var(--primary-border, #cfe0ff); background: var(--primary-soft); color: var(--primary); }
.sf-arrow { color: var(--text-muted); }
.sf-note { color: var(--text-muted); }

/* 危险区 */
.danger-zone { border: 1px solid #fecaca; border-radius: 14px; overflow: hidden; margin-bottom: 18px; }
.dz-head { background: #fef2f2; padding: 14px 20px; border-bottom: 1px solid #fecaca; }
.dz-title { font-size: 14px; font-weight: 700; color: #b91c1c; }
.dz-desc { font-size: 12px; color: #ef4444; margin-top: 2px; }
.dz-row { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-top: 1px solid #fee2e2; }
.dz-row:first-child { border-top: none; }
.dz-r-t { font-size: 13.5px; font-weight: 600; color: var(--slate); }
.dz-r-s { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
.dz-warn { color: var(--red); font-weight: 600; }
.dz-action { display: flex; align-items: center; gap: 8px; }
.btn-danger { background: #fff; color: var(--red); border: 1px solid #fecaca; border-radius: 9px; padding: 8px 14px; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn-danger:hover:not(:disabled) { background: #fef2f2; border-color: #fca5a5; }
.btn-danger:disabled { cursor: not-allowed; opacity: 0.5; }

/* 弹窗 */
.modal-mask { position: fixed; inset: 0; background: rgba(15, 31, 61, 0.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { width: 440px; background: #fff; border-radius: 16px; box-shadow: var(--shadow-lg); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 0; }
.modal-title { font-size: 16px; font-weight: 700; color: var(--navy); margin: 0; }
.modal-close { border: none; background: none; font-size: 16px; color: var(--text-muted); cursor: pointer; }
.modal-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field > span { font-size: 12.5px; font-weight: 600; color: var(--slate); }
.field input, .field select { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; font-size: 13px; }
.field input:focus, .field select:focus { outline: none; border-color: var(--primary); }
.pii-note { font-size: 11px; color: var(--text-muted); line-height: 1.6; background: #fbfcfe; border: 1px dashed var(--border); border-radius: 8px; padding: 9px 11px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 0 20px 18px; }

@media (max-width: 860px) {
  .layout { grid-template-columns: 1fr; }
  .side-nav { position: static; display: flex; overflow-x: auto; }
}
</style>
