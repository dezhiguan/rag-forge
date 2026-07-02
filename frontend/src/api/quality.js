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

export const fetchCost = (days, kbId) =>
  request.get('/evaluation/quality/cost', { params: { days, ...(kbId ? { kbId } : {}) } })

export const listSamplingConfigs = () => request.get('/evaluation/quality/sampling')

export const upsertSamplingConfig = (data) => request.post('/evaluation/quality/sampling', data)

export const deleteSamplingConfig = (id) => request.delete(`/evaluation/quality/sampling/${id}`)

export const fetchGoldenSetEnabledCount = () => request.get('/evaluation/golden-set/enabled-count')

export const replayGoldenSetNow = (params = {}) =>
  request.post('/evaluation/golden-set/replay', null, { params })
