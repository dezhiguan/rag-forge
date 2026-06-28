<template>
  <div>
    <div class="page-body">
      <div class="top-toolbar">
        <div class="toolbar-left">
          <button class="btn btn-primary" @click="openCreate">+ 创建组织</button>
          <button class="btn btn-secondary" :disabled="loading" @click="loadOrgs">刷新</button>
        </div>
      </div>

      <div v-if="loading" class="state-hint">加载中…</div>
      <div v-else-if="!orgs.length" class="empty-state">
        <div class="state-title">还没有组织</div>
        <div class="state-desc">创建组织后，可把知识库归属到组织，并邀请成员共享。</div>
      </div>

      <table v-else class="data-table">
        <thead>
          <tr>
            <th>组织名称</th>
            <th>标识</th>
            <th>我的角色</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="org in orgs" :key="org.id">
            <td>{{ org.name }}</td>
            <td><code>{{ org.slug }}</code></td>
            <td><span class="role-tag" :class="'role-' + (org.myRole || '').toLowerCase()">{{ roleLabel(org.myRole) }}</span></td>
            <td>
              <button
                v-if="isOrgAdmin(org)"
                class="btn btn-sm btn-secondary"
                @click="openMembers(org)"
              >
                管理成员
              </button>
              <span v-else class="muted">仅成员</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 创建组织 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h3 class="modal-title">创建组织</h3>
        <label class="field">
          <span>组织名称 *</span>
          <input v-model="createForm.name" type="text" placeholder="例如：广州日不落科技有限公司" />
        </label>
        <label class="field">
          <span>标识 slug *</span>
          <input v-model="createForm.slug" type="text" placeholder="小写字母/数字/连字符，如 rblk" />
        </label>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" :disabled="submitting" @click="onCreate">确定</button>
        </div>
      </div>
    </div>

    <!-- 成员管理 -->
    <div v-if="showMembers" class="modal-mask" @click.self="showMembers = false">
      <div class="modal modal-wide">
        <h3 class="modal-title">成员管理 · {{ activeOrg?.name }}</h3>

        <div class="add-member">
          <input v-model="memberForm.userId" type="number" placeholder="用户 ID" class="mini-input" />
          <select v-model="memberForm.role" class="mini-input">
            <option value="MEMBER">成员</option>
            <option value="ADMIN">管理员</option>
            <option v-if="activeOrgIsOwner" value="OWNER">所有者</option>
          </select>
          <button class="btn btn-sm btn-primary" :disabled="submitting" @click="onAddMember">添加成员</button>
        </div>

        <table class="data-table">
          <thead>
            <tr><th>用户 ID</th><th>角色</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="m in members" :key="m.userId">
              <td>{{ m.userId }}</td>
              <td>
                <select
                  :value="m.role"
                  class="mini-input"
                  @change="onChangeRole(m, $event.target.value)"
                >
                  <option value="MEMBER">成员</option>
                  <option value="ADMIN">管理员</option>
                  <option v-if="activeOrgIsOwner" value="OWNER">所有者</option>
                </select>
              </td>
              <td>
                <button class="btn btn-sm btn-danger" @click="onRemoveMember(m)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showMembers = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  listOrgs,
  createOrg,
  listMembers as listMembersApi,
  addMember as addMemberApi,
  updateMember as updateMemberApi,
  removeMember as removeMemberApi,
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

const activeOrgIsOwner = computed(() => activeOrg.value?.myRole === 'OWNER')

function roleLabel(role) {
  return { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }[role] || role || '-'
}
function isOrgAdmin(org) {
  return org?.myRole === 'OWNER' || org?.myRole === 'ADMIN'
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
  memberForm.value = { userId: null, role: 'MEMBER' }
  showMembers.value = true
  await loadMembers()
}

async function loadMembers() {
  const res = await listMembersApi(activeOrg.value.id)
  members.value = res?.data || res || []
}

async function onAddMember() {
  if (!memberForm.value.userId) {
    toast.warning('请输入用户 ID')
    return
  }
  submitting.value = true
  try {
    await addMemberApi(activeOrg.value.id, {
      userId: Number(memberForm.value.userId),
      role: memberForm.value.role,
    })
    memberForm.value = { userId: null, role: 'MEMBER' }
    await loadMembers()
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
    message: `确认把用户 ${member.userId} 移出组织？`,
    confirmText: '移除',
  })
  if (!ok) return
  await removeMemberApi(activeOrg.value.id, member.userId)
  await loadMembers()
}

onMounted(loadOrgs)
</script>

<style scoped>
.empty-state { padding: 48px; text-align: center; color: var(--muted, #64748b); }
.state-title { font-size: 16px; font-weight: 600; margin-bottom: 6px; }
.state-desc { font-size: 13px; }
.state-hint { padding: 24px; color: var(--muted, #64748b); }
.role-tag { display: inline-block; border-radius: 999px; padding: 1px 10px; font-size: 12px; font-weight: 600; }
.role-owner { background: #fef3c7; color: #92400e; }
.role-admin { background: #dbeafe; color: #1e40af; }
.role-member { background: #f1f5f9; color: #475569; }
.muted { color: var(--muted, #94a3b8); font-size: 13px; }
.add-member { display: flex; gap: 8px; margin-bottom: 14px; align-items: center; }
.mini-input { padding: 6px 8px; border: 1px solid var(--line, #e2e8f0); border-radius: 6px; font-size: 13px; }
.modal-wide { width: min(560px, 92vw); }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; }
</style>
