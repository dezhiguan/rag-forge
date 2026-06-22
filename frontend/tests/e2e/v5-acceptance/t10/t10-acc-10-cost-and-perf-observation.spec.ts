import {
  test,
  expect,
  asset,
  cleanupKb,
  createKb,
  fetchPrometheus,
  login,
  uploadFile,
  waitForStatus,
} from '../_helpers/t10-common'
import { parsePrometheusCounter, parsePrometheusSampleCount } from '../_helpers/t10-asserts'
import { T10_FIXTURES } from './fixtures/t10-fixtures'

test.describe.configure({ timeout: 300_000 })

test('T10 ACC-10 @e2e-dashscope-real mixed PDF cost and performance observation', async ({ request }) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't10-acc-10', 'ON')
  try {
    const beforeText = await fetchPrometheus(request, headers)
    const ocrBefore = parsePrometheusCounter(beforeText, 'ragforge_ocr_qwen_vl_ocr_calls')
    const vlBefore = parsePrometheusCounter(beforeText, 'ragforge_embedding_vl_calls')

    const started = Date.now()
    const docId = await uploadFile(request, headers, kbId, asset(T10_FIXTURES.pdfMixedRich))
    await waitForStatus(request, headers, docId, 'COMPLETED', 300_000)
    const elapsedMs = Date.now() - started

    const afterText = await fetchPrometheus(request, headers)
    const ocrAfter = parsePrometheusCounter(afterText, 'ragforge_ocr_qwen_vl_ocr_calls')
    const vlAfter = parsePrometheusCounter(afterText, 'ragforge_embedding_vl_calls')
    const imageWorkerSamples = parsePrometheusSampleCount(
      afterText,
      'ragforge_worker_processing_duration_seconds_bucket{modality="image"',
    )

    const ocrDelta = ocrAfter - ocrBefore
    const vlDelta = vlAfter - vlBefore
    const estimatedCostCny = ocrDelta * 0.02 + vlDelta * 0.005

    expect(ocrDelta).toBeGreaterThanOrEqual(5)
    expect(vlDelta).toBeGreaterThanOrEqual(10)
    expect(imageWorkerSamples + parsePrometheusSampleCount(afterText, 'ragforge_worker_processing_duration_seconds_count{modality="image"')).toBeGreaterThan(0)
    expect(elapsedMs).toBeLessThan(60_000)
    expect(estimatedCostCny).toBeLessThanOrEqual(0.5)

    test.info().annotations.push({
      type: 'cost-estimate',
      description: `ocrDelta=${ocrDelta}, vlDelta=${vlDelta}, elapsedMs=${elapsedMs}, estimatedCostCny=${estimatedCostCny.toFixed(3)}`,
    })
  } finally {
    await cleanupKb(request, headers, kbId)
  }
})
