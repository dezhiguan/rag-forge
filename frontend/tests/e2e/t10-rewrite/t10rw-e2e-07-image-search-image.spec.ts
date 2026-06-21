import { expect, test } from './fixtures/t10rw-test'
import fs from 'node:fs'
import { asset, cleanupKb, createKb, login, searchByImage, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-7 @e2e-dashscope-real image query recalls similar architecture images', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-image-search-image', 'OFF')
  try {
    const doc1 = await uploadFile(request, headers, kbId, asset('architecture_v1.png'), { externalId: 'arch-v1' })
    const doc2 = await uploadFile(request, headers, kbId, asset('architecture_v2.png'), { externalId: 'arch-v2' })
    await waitForStatus(request, headers, doc1, 'COMPLETED')
    await waitForStatus(request, headers, doc2, 'COMPLETED')
    const results = await searchByImage(request, headers, kbId, fs.readFileSync(asset('architecture_v1.png')), 5)
    expect(results.slice(0, 2).map((r) => r.docId).sort()).toEqual([doc1, doc2].sort())
    expect(results[0].vectorScore || 0).toBeGreaterThan(0.85)
    expect(Math.max(...results.filter((r) => r.docId === doc2).map((r) => r.vectorScore || 0))).toBeGreaterThan(0.6)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
