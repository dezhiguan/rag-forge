import request from './request'

/** 模型列表（含本月费用）。 */
export function getModelList() {
  return request({ url: '/models', method: 'get' })
}

/** 启用/停用模型。停用后该用途无可用模型时后端返回 409。 */
export function toggleModel(code, enabled) {
  return request({ url: `/models/${code}/toggle`, method: 'put', data: { enabled } })
}

/** 成本看板：KPI + 趋势 + 排行。 */
export function getModelCostStats(days = 7) {
  return request({ url: '/models/cost/stats', method: 'get', params: { days } })
}

/** 调用明细（按用途）。 */
export function getModelCostDetail() {
  return request({ url: '/models/cost/detail', method: 'get' })
}
