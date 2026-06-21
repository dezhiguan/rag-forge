import { expect, test } from './fixtures/t10rw-test'
import fs from 'node:fs'
import { asset, cleanupKb, createKb, login, searchByImage, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-8 @e2e-dashscope-real image query also recalls related TEXT chunks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-image-search-text', 'OFF')
  try {
    await uploadFile(request, headers, kbId, asset('architecture_v1.png'))
    const textDoc = await uploadFile(request, headers, kbId, asset('text_only.pdf'))
    await waitForStatus(request, headers, textDoc, 'COMPLETED')
    const results = await searchByImage(request, headers, kbId, fs.readFileSync(asset('architecture_v1.png')), 10)
    expect(results.some((r) => r.chunkModality === 'IMAGE')).toBeTruthy()
    const textHitIndex = results.findIndex((r) => r.chunkModality === 'TEXT' && /(Spring Boot|architecture|架构)/i.test(r.content || ''))
    expect(textHitIndex).toBeGreaterThanOrEqual(0)
    expect(textHitIndex).toBeLessThan(5)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
