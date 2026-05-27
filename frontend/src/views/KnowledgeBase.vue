<template>
  <div>
    <div class="page-body">
      <div class="toolbar">
        <button class="btn-primary" @click="openCreate">+ 创建知识库</button>
        <button class="btn-ghost" :disabled="loading" @click="loadList">刷新</button>
      </div>

      <div v-if="loading" class="state-hint">加载中…</div>
      <div v-else-if="!kbList.length" class="state-hint">暂无知识库，点击上方按钮创建</div>

      <table v-else class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>文档数</th>
            <th>Chunk 数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="kb in kbList" :key="kb.id">
            <td>
              <strong>{{ kb.name }}</strong>
              <div v-if="kb.description" class="desc">{{ kb.description }}</div>
            </td>
            <td>{{ kb.docCount ?? 0 }}</td>
            <td>{{ kb.chunkCount ?? 0 }}</td>
            <td><span class="badge" :class="statusClass(kb.status)">{{ statusLabel(kb.status) }}</span></td>
            <td>{{ formatTime(kb.createdAt) }}</td>
            <td>
              <span class="link-action danger" @click="onDelete(kb)">删除</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h3 class="modal-title">创建知识库</h3>
        <label class="field">
          <span>名称 *</span>
          <input v-model="form.name" type="text" placeholder="例如：产品文档库" />
        </label>
        <label class="field">
          <span>描述</span>
          <textarea v-model="form.description" rows="3" placeholder="可选" />
        </label>
        <div class="modal-actions">
          <button class="btn-ghost" @click="showCreate = false">取消</button>
          <button class="btn-primary" :disabled="submitting" @click="onCreate">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { createKb, deleteKb, listKb } from '../api/kb'

const kbList = ref([])
const loading = ref(false)
const showCreate = ref(false)
const submitting = ref(false)
const form = ref({ name: '', description: '' })

async function loadList() {
  loading.value = true
  try {
    const res = await listKb()
    kbList.value = res.data ?? []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', description: '' }
  showCreate.value = true
}

async function onCreate() {
  if (!form.value.name?.trim()) {
    alert('请填写知识库名称')
    return
  }
  submitting.value = true
  try {
    await createKb({
      name: form.value.name.trim(),
      description: form.value.description || undefined,
    })
    showCreate.value = false
    await loadList()
  } finally {
    submitting.value = false
  }
}

async function onDelete(kb) {
  if (!confirm(`确定删除知识库「${kb.name}」？`)) return
  try {
    await deleteKb(kb.id)
    await loadList()
  } catch {
    /* 错误已在 request 拦截器提示 */
  }
}

function formatTime(iso) {
  if (!iso) return '-'
  return iso.replace('T', ' ').slice(0, 19)
}

function statusLabel(status) {
  const map = { active: '可用', deleted: '已删除', rebuilding: '重建中' }
  return map[status] || status || '-'
}

function statusClass(status) {
  if (status === 'active') return 'badge-green'
  if (status === 'rebuilding') return 'badge-amber'
  return 'badge-gray'
}

onMounted(loadList)
</script>

<style scoped>
.page-body { padding: 20px 28px 32px; }

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.btn-primary {
  background: var(--blue);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }

.btn-ghost {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}
.btn-ghost:disabled { opacity: 0.5; }

.state-hint {
  text-align: center;
  color: var(--text-muted);
  padding: 48px 0;
  font-size: 14px;
}

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; background: #fff; border-radius: 10px; overflow: hidden; }
.data-table th, .data-table td { text-align: left; padding: 12px 14px; border-bottom: 1px solid var(--border); }
.data-table th { background: var(--light); font-weight: 600; color: var(--slate); font-size: 11px; text-transform: uppercase; }
.data-table tbody tr:hover { background: #f8fafc; }
.desc { font-size: 11px; color: var(--text-muted); margin-top: 2px; font-weight: normal; }

.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}
.badge-green { background: #dcfce7; color: #166534; }
.badge-amber { background: #fef3c7; color: #92400e; }
.badge-gray { background: #f1f5f9; color: #64748b; }

.link-action { color: var(--blue); cursor: pointer; font-size: 12px; }
.link-action.danger { color: var(--red); }

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: min(420px, 92vw);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.15);
}
.modal-title { font-size: 16px; margin-bottom: 16px; }
.field { display: block; margin-bottom: 14px; }
.field span { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
.field input, .field textarea {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
}
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
</style>
