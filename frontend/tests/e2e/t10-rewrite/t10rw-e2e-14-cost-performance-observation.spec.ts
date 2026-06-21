import { expect, test } from './fixtures/t10rw-test'

test.describe.configure({ timeout: 240_000 })

test('E2E-14 @e2e-dashscope-real cost and performance observation placeholders are recorded', async () => {
  const before = Number(process.env.DASHSCOPE_E2E_COST_BEFORE_CNY || 0)
  const after = Number(process.env.DASHSCOPE_E2E_COST_AFTER_CNY || 0)
  const mixedPdfMs = Number(process.env.T10RW_E2E_MIXED_PDF_MS || 0)
  if (after > 0 || before > 0) {
    expect(after - before).toBeLessThan(10)
  }
  if (mixedPdfMs > 0) {
    expect(mixedPdfMs).toBeLessThan(90_000)
  }
  test.info().annotations.push({
    type: 'manual-observation',
    description: 'PR must attach DashScope qwen-vl-ocr/qwen3-vl-embedding before/after cost screenshot and confirm no 429.',
  })
})
