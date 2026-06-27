import { DEV_SMS_CODE } from './env'

/** 本地 QA 矩阵（与 unified-auth-test-plan-V1.md A1 对齐，可按环境变量覆盖） */
export const ADMIN = {
  alias: 'ADMIN',
  phone: process.env.QA_ADMIN_PHONE || '13800000000',
  account: process.env.QA_ADMIN_ACCOUNT || 'admin',
  password: process.env.QA_ADMIN_PASSWORD || 'Admin123!',
  smsCode: DEV_SMS_CODE,
}

export const U_RAG = {
  alias: 'U_RAG',
  phone: process.env.QA_U_RAG_PHONE || '13800138002',
  username: process.env.QA_U_RAG_USER || 'qa_u_rag',
  email: process.env.QA_U_RAG_EMAIL || 'qa_u_rag@test.local',
  password: process.env.QA_U_RAG_PASSWORD || 'Str0ng#Pass1',
  smsCode: DEV_SMS_CODE,
  account: process.env.QA_U_RAG_USER || 'qa_u_rag',
}

export const U_OTHER = {
  alias: 'U_OTHER',
  phone: process.env.QA_U_OTHER_PHONE || '13800138003',
  username: process.env.QA_U_OTHER_USER || 'qa_u_other',
  email: process.env.QA_U_OTHER_EMAIL || 'qa_u_other@test.local',
  password: process.env.QA_U_OTHER_PASSWORD || 'Str0ng#Pass1',
  smsCode: DEV_SMS_CODE,
  account: process.env.QA_U_OTHER_USER || 'qa_u_other',
}

export const U_CM = {
  alias: 'U_CM',
  phone: process.env.QA_U_CM_PHONE || '13800138004',
  smsCode: DEV_SMS_CODE,
}

export const PHONE_NEW = process.env.QA_PHONE_NEW || '13800138999'
