import fs from 'node:fs'
import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  getChunks,
  login,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { imageChunks } from '../_helpers/t10-asserts'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 240_000 })

test('T10 ACC-07 @e2e-dashscope-real HTML img tags become IMAGE chunks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-07', 'ON')
  try {
    const html = fs.readFileSync(asset(T10_FIXTURES.htmlImgTag), 'utf8')
    const imgCount = (html.match(/<img\b/gi) || []).length
    expect(imgCount).toBeGreaterThan(0)

    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.htmlImgTag))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)

    const chunks = await getChunks(request, headers, docId)
    const images = imageChunks(chunks)
    expect(images).toHaveLength(imgCount)
    expect(images.some((chunk) => /Q1|收入|100w/.test(chunk.content || ''))).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
