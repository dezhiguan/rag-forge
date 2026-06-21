import { expect, test } from './fixtures/t10rw-test'
import { asset, cleanupKb, createKb, getChunkRaw, login, uploadFile, waitForStatus } from './fixtures/helpers'

test.describe.configure({ timeout: 240_000 })

test('E2E-9 @e2e-dashscope-real KB image_processing_mode OFF skips embedded images', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10rw-image-mode-off', 'OFF')
  try {
    const docId = await uploadFile(request, headers, kbId, asset('mixed_3figures.pdf'))
    await waitForStatus(request, headers, docId, 'COMPLETED')
    const raw = await getChunkRaw(request, headers, docId)
    expect(raw.filter((c) => c.modality === 'TEXT').length).toBeGreaterThan(0)
    expect(raw.filter((c) => c.modality === 'IMAGE')).toHaveLength(0)
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
