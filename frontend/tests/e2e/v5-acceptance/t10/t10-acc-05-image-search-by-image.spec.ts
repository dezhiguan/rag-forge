import fs from 'node:fs'
import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  login,
  searchByImage,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 300_000 })

test('T10 ACC-05 @e2e-dashscope-real search by image recalls similar IMAGE chunks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-05', 'OFF')
  try {
    const docA = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.catA), { externalId: 'cat-a' })
    const docB = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.catB), { externalId: 'cat-b' })
    const docC = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.catC), { externalId: 'cat-c' })
    await waitForStatus(request, headers, docA, 'COMPLETED')
    await waitForStatus(request, headers, docB, 'COMPLETED')
    await waitForStatus(request, headers, docC, 'COMPLETED')

    const results = await searchByImage(request, headers, kbId, fs.readFileSync(asset(T10_FIXTURES.catQuery)), 8)
    const imageHits = results.filter((r) => r.chunkModality === 'IMAGE')
    expect(imageHits.length).toBeGreaterThanOrEqual(2)

    const scores = imageHits.map((r) => r.vectorScore ?? r.finalScore ?? 0)
    for (let i = 1; i < scores.length; i += 1) {
      expect(scores[i - 1]).toBeGreaterThanOrEqual(scores[i])
    }
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
