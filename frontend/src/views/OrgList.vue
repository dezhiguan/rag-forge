<template>
  <div class="page-body org-list">
    <div class="page-head">
      <div>
        <h1 class="page-h">我的组织</h1>
        <div class="page-s">你创建或加入的组织。创建团队组织后可邀请成员、共享组织知识库、按组织聚合指标。</div>
      </div>
      <button class="btn-primary" @click="openCreate = true">＋ 创建组织</button>
    </div>

    <div class="sec-label">团队组织（{{ teamOrgs.length }}）</div>

    <div v-if="teamOrgs.length" class="grid">
      <div v-for="o in teamOrgs" :key="o.id" class="org-card">
        <div class="oc-top">
          <span class="oc-ava" :style="{ background: avatarColor(o.id) }">{{ initial(o.name) }}</span>
          <div class="oc-hd">
            <div class="oc-name">{{ o.name }} <span class="role-badge" :class="roleClass(o.myRole)">{{ o.myRole }}</span></div>
            <div class="oc-slug">@{{ o.slug }}</div>
          </div>
        </div>
        <div class="oc-stats">
          <div class="oc-stat"><div class="n">{{ o.memberCount ?? '—' }}</div><div class="l">成员</div></div>
          <div class="oc-stat"><div class="n">{{ o.kbCount ?? '—' }}</div><div class="l">知识库</div></div>
        </div>
        <div class="oc-foot">
          <span class="oc-date">{{ fmtDate(o.createdAt) }}</span>
          <div class="oc-actions">
            <button class="btn" @click="enter(o)">{{ canManage(o) ? '管理 →' : '进入 →' }}</button>
            <button v-if="o.myRole === 'OWNER'" class="btn btn-danger" @click="onDelete(o)">删除</button>
            <button v-else class="btn btn-danger" @click="onLeave(o)">退出</button>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="empty">
      <span class="ei">🏢</span>你还没有加入任何团队组织
      <div><button class="btn-primary" style="margin-top:14px" @click="openCreate = true">＋ 创建第一个组织</button></div>
    </div>

    <!-- 创建组织 -->
    <div v-if="openCreate" class="mask" @click.self="openCreate = false">
      <div class="modal">
        <h3>创建组织</h3>
        <div class="fld"><label>组织名称</label><input v-model="createForm.name" placeholder="如：广州日不落科技有限公司" /></div>
        <div class="fld"><label>slug（短标识，小写字母/数字/-）</label><input v-model="createForm.slug" placeholder="如：rbl" /></div>
        <div class="modal-foot">
          <button class="btn" @click="openCreate = false">取消</button>
          <button class="btn-primary" :disabled="creating || !createForm.name.trim() || !createForm.slug.trim()" @click="onCreate">
            {{ creating ? '创建中…' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { createOrg, deleteOrg, leaveOrg } from '../api/org'
import { useOrg } from '../composables/useOrg'
import { useAuth } from '../composables/useAuth'
import { useToast } from '../composables/useToast'
import { confirm as confirmDialog } from '../composables/useConfirm'

const router = useRouter()
const toast = useToast()
const { orgs, load, setCurrent } = useOrg()
const { ragRole } = useAuth()

// 个人组织(personal)不在"团队组织"列表里展示，通过左上切换器进入。
const teamOrgs = computed(() => orgs.value.filter((o) => o.id != null && !o.personal))

const openCreate = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', slug: '' })

const PALETTE = ['#2563eb', '#15803d', '#7c3aed', '#db2777', '#0ea5e9', '#f59e0b']
function avatarColor(id) {
  return PALETTE[Math.abs(Number(id) || 0) % PALETTE.length]
}
function initial(name) {
  return (name || '?').trim().charAt(0).toUpperCase()
}
function roleClass(role) {
  return { OWNER: 'r-owner', ADMIN: 'r-admin', MEMBER: 'r-member' }[role] || 'r-member'
}
function canManage(o) {
  return o.myRole === 'OWNER' || o.myRole === 'ADMIN'
}
function fmtDate(s) {
  if (!s) return '—'
  return `创建于 ${String(s).slice(0, 10)}`
}

async function reload() {
  await load({ isAdmin: ragRole.value === 'ADMIN' })
}

function enter(o) {
  setCurrent(o.id)
  router.push('/orgs/manage')
}

async function onCreate() {
  if (creating.value) return
  creating.value = true
  try {
    await createOrg({ name: createForm.value.name.trim(), slug: createForm.value.slug.trim() })
    openCreate.value = false
    createForm.value = { name: '', slug: '' }
    await reload()
    toast.success('组织已创建')
  } catch (e) {
    /* 错误由全局拦截提示 */
  } finally {
    creating.value = false
  }
}

async function onDelete(o) {
  if (o.kbCount > 0) {
    toast.error('请先把组织名下知识库转出或删除，再删除组织')
    return
  }
  const ok = await confirmDialog({
    title: '删除组织',
    message: `确认删除组织「${o.name}」？成员关系将一并清除，删除后不可恢复。`,
    confirmText: '删除组织',
  })
  if (!ok) return
  await deleteOrg(o.id)
  await reload()
  toast.success('组织已删除')
}

async function onLeave(o) {
  const ok = await confirmDialog({
    title: '退出组织',
    message: `确认退出「${o.name}」？退出后将不再能访问该组织的知识库。`,
    confirmText: '退出',
  })
  if (!ok) return
  await leaveOrg(o.id)
  await reload()
  toast.success('已退出组织')
}

reload()
</script>

<style scoped>
.org-list { padding: 20px 28px 40px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 18px; }
.page-h { font-size: 19px; font-weight: 700; margin: 0 0 4px; color: var(--navy); }
.page-s { font-size: 12.5px; color: var(--text-muted); }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 16px; border: none; border-radius: 9px; background: var(--primary); color: #fff; font-size: 13px; font-weight: 600; cursor: pointer; box-shadow: 0 1px 2px rgba(37, 99, 235, 0.28); }
.btn-primary:hover:not(:disabled) { background: var(--primary-hover); }
.btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }
.btn { height: 32px; padding: 0 12px; border: 1px solid var(--border); background: #fff; color: var(--slate); border-radius: 8px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
.btn:hover { border-color: var(--primary-border); }
.btn-danger { color: var(--red); border-color: #fecaca; }
.btn-danger:hover { background: #fef2f2; }


.sec-label { font-size: 12px; font-weight: 700; color: var(--text-muted); letter-spacing: .4px; margin: 6px 2px 12px; }

.grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
.org-card { background: var(--surface); border: 1px solid var(--border); border-radius: 16px; box-shadow: var(--shadow-sm); padding: 18px 20px; display: flex; flex-direction: column; transition: transform .16s, box-shadow .16s, border-color .16s; }
.org-card:hover { transform: translateY(-3px); box-shadow: var(--shadow-md); border-color: #d8e2ef; }
.oc-top { display: flex; align-items: center; gap: 12px; }
.oc-ava { width: 44px; height: 44px; border-radius: 12px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 700; flex-shrink: 0; }
.oc-hd { flex: 1; min-width: 0; }
.oc-name { font-size: 15px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
.role-badge { font-size: 10px; font-weight: 700; padding: 1px 7px; border-radius: 999px; }
.r-owner { background: #fef3c7; color: #92400e; } .r-admin { background: #e0e7ff; color: #3730a3; } .r-member { background: #f1f5f9; color: #475569; }
.oc-slug { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
.oc-stats { display: flex; gap: 22px; margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--border); }
.oc-stat .n { font-size: 16px; font-weight: 700; } .oc-stat .l { font-size: 11px; color: var(--text-muted); }
.oc-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; }
.oc-date { font-size: 11.5px; color: var(--text-muted); }
.oc-actions { display: flex; gap: 8px; }
.empty { padding: 48px 0; text-align: center; color: var(--text-muted); } .empty .ei { font-size: 34px; display: block; margin-bottom: 12px; }

.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { width: 420px; background: #fff; border-radius: 16px; box-shadow: var(--shadow-md); padding: 22px; }
.modal h3 { margin: 0 0 16px; font-size: 16px; }
.fld { margin-bottom: 14px; } .fld label { display: block; font-size: 12.5px; font-weight: 600; color: var(--slate); margin-bottom: 6px; }
.fld input { width: 100%; border: 1px solid var(--border); border-radius: 9px; padding: 9px 12px; font-size: 13px; }
.fld input:focus { outline: none; border-color: var(--primary); }
.modal-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
</style>
