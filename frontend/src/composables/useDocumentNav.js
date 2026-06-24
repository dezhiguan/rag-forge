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
  const resolvedKbId = resolveKbId(kbId)
  if (resolvedKbId != null) query.kbId = String(resolvedKbId)
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
