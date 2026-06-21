import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunkRaw, login, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-3 @e2e-dashscope-real mixed PDF stores TEXT chunks plus three IMAGE chunks', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-mixed-pdf', 'ON')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('mixed_3figures.pdf'))
    await waitForStatus(request, headers, docId, 'COMPLETED', 240_000)
    const raw = await getChunkRaw(request, headers, docId)
    expect(raw.filter((c) => c.modality === 'TEXT').length).toBeGreaterThan(0)
    expect(raw.filter((c) => c.modality === 'IMAGE')).toHaveLength(3)
    expect(raw.every((c) => c.vlVectorDim === 2560)).toBeTruthy()
    expect(raw.some((c) => c.modality === 'IMAGE' && (c.chunkMetadataJson || '').includes('pageNo'))).toBeTruthy()
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
