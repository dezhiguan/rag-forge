export function documentDetailRoute(docId, { from, kbId } = {}) {
  const query = {}
  if (from) query.from = from
  if (kbId != null) query.kbId = String(kbId)
  return { path: `/document/${docId}`, query }
}

export function navigateBackFromDocument(router, route, kbIdFallback) {
  const from = route.query.from
  const kbId = Number(route.query.kbId) || kbIdFallback

  if (from === 'documents' && kbId) {
    return router.push(`/knowledge/${kbId}/documents`)
  }
  if (from === 'knowledge') {
    return router.push('/knowledge')
  }
  if (window.history.length > 1) {
    return router.back()
  }
  if (kbId) {
    return router.push(`/knowledge/${kbId}/documents`)
  }
  return router.push('/knowledge')
}
