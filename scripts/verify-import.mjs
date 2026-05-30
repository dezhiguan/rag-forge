#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'

const apiBase = process.env.API_BASE || 'http://127.0.0.1:8080/api/v1'
const apiKey = process.env.API_KEY || 'sk-ragforge-dev'
const pollIntervalMs = Number(process.env.POLL_INTERVAL_MS || 2000)
const timeoutMs = Number(process.env.TIMEOUT_MS || 180000)
const kbName = process.env.KB_NAME || `导入验证-${new Date().toISOString().slice(0, 19)}`
const skipMaintenance = process.env.SKIP_MAINTENANCE === 'true'

const files = process.argv.slice(2)
if (!files.length) {
  console.error('Usage: API_BASE=http://127.0.0.1:8080/api/v1 API_KEY=sk-ragforge-dev node scripts/verify-import.mjs <file...>')
  process.exit(1)
}

async function request(pathname, options = {}) {
  const headers = new Headers(options.headers || {})
  headers.set('X-API-Key', apiKey)
  const res = await fetch(`${apiBase}${pathname}`, { ...options, headers })
  const text = await res.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  if (!res.ok) {
    throw new Error(`${options.method || 'GET'} ${pathname} failed: HTTP ${res.status} ${text}`)
  }
  return body
}

async function createKnowledgeBase() {
  const body = await request('/kb', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: kbName,
      description: '自动导入验证知识库',
      chunkSize: 512,
      chunkOverlap: 64,
    }),
  })
  return body?.data?.id
}

async function uploadFile(kbId, filePath) {
  const form = new FormData()
  const bytes = fs.readFileSync(filePath)
  form.set('file', new Blob([bytes]), path.basename(filePath))
  const startedAt = Date.now()
  const body = await request(`/kb/${kbId}/documents?overwrite=true`, {
    method: 'POST',
    body: form,
  })
  const documentId = body?.data?.documentId || body?.data?.document?.id
  if (!documentId) {
    throw new Error(`Upload response missing documentId: ${JSON.stringify(body)}`)
  }
  return { documentId, filePath, startedAt }
}

async function pollDocument(item) {
  const deadline = item.startedAt + timeoutMs
  while (Date.now() < deadline) {
    const body = await request(`/documents/${item.documentId}/status`)
    const status = body?.data?.parseStatus
    const chunkCount = body?.data?.chunkCount ?? 0
    if (status === 'completed' || status === 'failed') {
      return {
        ...item,
        status,
        chunkCount,
        elapsedMs: Date.now() - item.startedAt,
        errorMsg: body?.data?.errorMsg,
      }
    }
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs))
  }
  return {
    ...item,
    status: 'timeout',
    chunkCount: 0,
    elapsedMs: Date.now() - item.startedAt,
  }
}

async function runMaintenanceChecks() {
  if (skipMaintenance) {
    return
  }
  const calibration = await request('/admin/maintenance/calibrate', { method: 'POST' })
  const calibrationData = calibration?.data ?? {}
  console.log(
    `calibration checkedDocs=${calibrationData.checkedDocuments ?? 0} fixedDocs=${calibrationData.fixedDocuments ?? 0} missingVectorDocs=${calibrationData.documentsMissingVector ?? 0} statusMismatchDocs=${calibrationData.documentsStatusMismatch ?? 0} fixedKbs=${calibrationData.fixedKnowledgeBases ?? 0} issues=${calibrationData.issues?.length ?? 0}`,
  )

  const repair = await request('/admin/maintenance/repair-es', { method: 'POST' })
  const repairData = repair?.data ?? {}
  console.log(
    `esRepair checkedDocs=${repairData.checkedDocuments ?? 0} repairedDocs=${repairData.repairedDocuments ?? 0} skippedDocs=${repairData.skippedDocuments ?? 0} failedDocs=${repairData.failedDocuments ?? 0} items=${repairData.items?.length ?? 0}`,
  )
  if ((repairData.failedDocuments ?? 0) > 0) {
    process.exitCode = 3
  }
}

async function main() {
  console.log(`API_BASE=${apiBase}`)
  const health = await request('/health')
  console.log(`health=${JSON.stringify(health?.data ?? health)}`)

  const kbId = process.env.KB_ID ? Number(process.env.KB_ID) : await createKnowledgeBase()
  if (!kbId) {
    throw new Error('Missing KB id')
  }
  console.log(`kbId=${kbId}`)

  const uploads = []
  for (const file of files) {
    const absolute = path.resolve(file)
    if (!fs.existsSync(absolute)) {
      throw new Error(`File not found: ${absolute}`)
    }
    const item = await uploadFile(kbId, absolute)
    uploads.push(item)
    console.log(`uploaded documentId=${item.documentId} file=${absolute}`)
  }

  const results = []
  for (const item of uploads) {
    const result = await pollDocument(item)
    results.push(result)
    console.log(
      `documentId=${result.documentId} status=${result.status} chunks=${result.chunkCount} elapsedMs=${result.elapsedMs} file=${result.filePath}`,
    )
    if (result.errorMsg) {
      console.log(`  error=${result.errorMsg}`)
    }
  }

  const ok = results.every((item) => item.status === 'completed')
  if (!ok) {
    process.exitCode = 2
  }
  await runMaintenanceChecks()
}

main().catch((err) => {
  console.error(err.message)
  process.exit(1)
})
