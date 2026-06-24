function firstQueryValue(value) {
  if (Array.isArray(value)) return value[0]
  return value
}

export function parsePositiveId(value) {
  const parsed = Number(firstQueryValue(value))
  if (Number.isInteger(parsed) && parsed > 0) return parsed
  return null
}

function resolveKbId(queryKbId, fallback) {
  const fromQuery = parsePositiveId(queryKbId)
  if (fromQuery != null) return fromQuery
  return parsePositiveId(fallback)
}

function resolveBackPath(route, kbId) {
  const returnTo = firstQueryValue(route.query.returnTo)
  if (typeof returnTo !== 'string' || !returnTo) return null
  // 只接受白名单路径，避免 open-redirect
  if (returnTo === '/knowledge') return '/knowledge'
  if (returnTo.startsWith('/knowledge/') && returnTo.includes('/documents')) {
    return returnTo
  }
  if (kbId && returnTo === `/knowledge/${kbId}`) return returnTo
  return null
}

/**
 * 兜底导航：先尝试 Vue Router，500ms 内未离开当前路由就硬刷新。
 * 解决 transition mode=out-in + Teleport 残留组合下偶发的空白页问题。
 */
function bulletproofNavigate(router, targetPath) {
  if (!targetPath) targetPath = '/knowledge'
  const currentPath = router.currentRoute.value.fullPath
  if (currentPath === targetPath) {
    // 已经在目标页（理论上不会进来），强制刷新一次
    window.location.assign(targetPath)
    return
  }
  let resolved = false
  router
    .push(targetPath)
    .then(() => {
      resolved = true
    })
    .catch(() => {
      // 路由失败（守卫拒绝 / NavigationFailure 等）直接硬跳
      window.location.assign(targetPath)
    })
  // 500ms 内 router 仍没切换 → 强制硬刷新兜底
  setTimeout(() => {
    if (resolved) return
    if (router.currentRoute.value.fullPath === currentPath) {
      window.location.assign(targetPath)
    }
  }, 500)
}

export function documentDetailRoute(docId, { from, kbId, returnTo } = {}) {
  const query = {}
  if (from) query.from = from
  const resolvedKbId = resolveKbId(kbId)
  if (resolvedKbId != null) query.kbId = String(resolvedKbId)
  if (returnTo) query.returnTo = returnTo
  return { path: `/document/${docId}`, query }
}

export function navigateBackFromDocument(router, route, kbIdFallback) {
  const from = firstQueryValue(route.query.from)
  const kbId = resolveKbId(route.query.kbId, kbIdFallback)
  const explicitReturn = resolveBackPath(route, kbId)

  let target
  if (explicitReturn) {
    target = explicitReturn
  } else if (from === 'documents' && kbId) {
    target = `/knowledge/${kbId}/documents`
  } else if (from === 'knowledge') {
    target = '/knowledge'
  } else if (kbId) {
    target = `/knowledge/${kbId}/documents`
  } else {
    target = '/knowledge'
  }
  bulletproofNavigate(router, target)
}
