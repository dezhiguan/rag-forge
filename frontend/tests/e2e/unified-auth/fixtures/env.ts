export const RAG_WEB = (process.env.RAG_WEB || process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173').replace(/\/$/, '')
export const RAG_API = (process.env.RAG_API || `${RAG_WEB}/api`).replace(/\/$/, '')
export const GW_BASE = (process.env.GW_BASE || 'http://localhost:8090').replace(/\/$/, '')
export const DEV_SMS_CODE = process.env.DEV_SMS_CODE || '123456'
