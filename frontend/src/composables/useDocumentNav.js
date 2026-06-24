function firstQueryValue(value) {
  if (Array.isArray(value)) return value[0]
  return value
}

function resolveKbId(queryKbId, fallback) {
  const fromQuery = Number(firstQueryValue(queryKbId))
  if (Number.isInteger(fromQuery) && fromQuery > 0) return fromQuery
  const fromFallback = Number(fallback)
  if (Number.isInteger(fromFallback) && fromFallback > 0) return fromFallback
  return null
}

function safePush(router, path) {
  return router.push(path).catch(() => router.push('/knowledge'))
}

function resolveBackPath(route, kbId) {
  const returnTo = firstQueryValue(route.query.returnTo)
  if (typeof returnTo === 'string' && returnTo.startsWith('/knowledge/') && returnTo.includes('/documents')) {
    return returnTo
  }
  return null
}

export function documentDetailRoute(docId, { from, kbId, returnTo } = {}) {
  const query = {}
  if (from) query.from = from
  if (kbId != null) query.kbId = String(kbId)
  if (returnTo) query.returnTo = returnTo
  return { path: `/document/${docId}`, query }
}

export function navigateBackFromDocument(router, route, kbIdFallback) {
  const from = firstQueryValue(route.query.from)
  const kbId = resolveKbId(route.query.kbId, kbIdFallback)
  const explicitReturn = resolveBackPath(route, kbId)

  if (explicitReturn) {
    return safePush(router, explicitReturn)
  }

  if (from === 'documents' && kbId) {
    return safePush(router, `/knowledge/${kbId}/documents`)
  }
  if (from === 'knowledge') {
    return safePush(router, '/knowledge')
  }
  if (kbId) {
    return safePush(router, `/knowledge/${kbId}/documents`)
  }
  return safePush(router, '/knowledge')
}
