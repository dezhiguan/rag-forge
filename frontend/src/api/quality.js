import request from './request'

export const fetchOverview = (days, kbId) =>
  request.get('/evaluation/quality/overview', {
    params: {
      days,
      ...(kbId != null ? { kbId } : {}),
    },
  })

export const fetchByKb = (days) => request.get('/evaluation/quality/by-kb', { params: { days } })

export const fetchWorstCases = (limit, days, kbId) =>
  request.get('/evaluation/quality/worst-cases', {
    params: {
      limit,
      days,
      ...(kbId != null ? { kbId } : {}),
    },
  })

export const fetchCaseDetail = (id) => request.get(`/evaluation/quality/case/${id}`)

export const fetchCost = (days) => request.get('/evaluation/quality/cost', { params: { days } })
