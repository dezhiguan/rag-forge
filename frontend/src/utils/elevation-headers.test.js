import assert from 'node:assert/strict'
import test from 'node:test'
import {
  resolveContextHeaders,
  ELEVATION_KEY,
  ELEVATION_REASON_KEY,
  CURRENT_ORG_KEY,
} from './elevation-headers.js'

// 用一个简单的 Map 模拟 localStorage 读取器。
function store(obj) {
  return { getItem: (k) => (k in obj ? obj[k] : null) }
}

test('提权态：注入 X-Admin-Override + 编码后的理由头', () => {
  const h = resolveContextHeaders(
    store({ [ELEVATION_KEY]: '1', [ELEVATION_REASON_KEY]: '排查组织质量下降', [CURRENT_ORG_KEY]: '64' }),
  )
  assert.equal(h['X-Admin-Override'], 'true')
  assert.equal(h['X-Admin-Override-Reason'], encodeURIComponent('排查组织质量下降'))
  // 提权时不再带组织头（走全平台）
  assert.equal(h['X-Org-Id'], undefined)
})

test('提权态但理由缺失：理由头兜底 platform-view', () => {
  const h = resolveContextHeaders(store({ [ELEVATION_KEY]: '1' }))
  assert.equal(h['X-Admin-Override'], 'true')
  assert.equal(h['X-Admin-Override-Reason'], 'platform-view')
})

test('未提权 + 有当前组织：注入 X-Org-Id，不带破玻璃头', () => {
  const h = resolveContextHeaders(store({ [CURRENT_ORG_KEY]: '316' }))
  assert.equal(h['X-Org-Id'], '316')
  assert.equal(h['X-Admin-Override'], undefined)
})

test('个人组织/未选择：不带任何组织或提权头', () => {
  assert.deepEqual(resolveContextHeaders(store({ [CURRENT_ORG_KEY]: 'null' })), {})
  assert.deepEqual(resolveContextHeaders(store({ [CURRENT_ORG_KEY]: '' })), {})
  assert.deepEqual(resolveContextHeaders(store({})), {})
})

test('历史 platform 残留值：不再当作提权（已下线全局全平台视图）', () => {
  const h = resolveContextHeaders(store({ [CURRENT_ORG_KEY]: 'platform' }))
  assert.equal(h['X-Admin-Override'], undefined)
  assert.equal(h['X-Org-Id'], undefined)
})

test('入参非法：安全返回空头', () => {
  assert.deepEqual(resolveContextHeaders(null), {})
  assert.deepEqual(resolveContextHeaders({}), {})
})
