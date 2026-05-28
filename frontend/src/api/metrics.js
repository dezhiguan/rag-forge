import request from './request'

export const getDashboardMetrics = () => request.get('/metrics/dashboard')

