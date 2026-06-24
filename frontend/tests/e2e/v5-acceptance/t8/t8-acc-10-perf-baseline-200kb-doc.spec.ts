import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  deleteCleanProfile,
  getDocument,
  login,
  loginPage,
  parseCleanReport,
  uploadFile,
  waitForStatus,
  cleanupKb,
  screenshotPair,
} from '../_helpers/t8-common'
import { T8_FIXTURES } from './fixtures/t8-fixtures'

test.describe.configure({ timeout: 360_000 })

test('T8 ACC-10 200KB doc perf baseline', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const kbId = await createKb(request, headers, 't8-acc-10-perf')
  try {
    await createCleanProfile(request, headers, kbId, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'MASK',
    })

    // ensure doc list has at least 2 pages for pagination click checks
    for (let i = 0; i < 20; i++) {
      await uploadFile(request, headers, kbId, asset(T8_FIXTURES.pure))
    }

    const docId = await uploadFile(request, headers, kbId, asset(T8_FIXTURES.perf))
    const parseStart = Date.now()
    const parsePromise = waitForStatus(request, headers, docId, 'COMPLETED', 360_000).then(() => Date.now() - parseStart)

    const uiTab = await page.context().newPage()
    const paginationLatencies: number[] = []
    try {
      await loginPage(uiTab)
      await uiTab.goto(`/knowledge/${kbId}/documents`, { waitUntil: 'domcontentloaded' })
      await uiTab.waitForLoadState('networkidle')

      const nextButton = uiTab.getByRole('button', { name: '下一页' })
      const prevButton = uiTab.getByRole('button', { name: '上一页' })
      const refreshButton = uiTab.getByRole('button', { name: '刷新' }).first()

      await refreshButton.waitFor({ state: 'visible', timeout: 20_000 })

      let direction = 1
      for (let step = 0; step < 8; step++) {
        const target = direction > 0 ? nextButton : prevButton
        const hasPager = (await target.count()) > 0
        const enabled = hasPager && (await target.isEnabled())
        if (!enabled) {
          const before = Date.now()
          await refreshButton.click()
          await uiTab.waitForTimeout(250)
          paginationLatencies.push(Date.now() - before)
          continue
        }

        const before = Date.now()
        await target.click()
        const loadingHint = uiTab.getByText('加载文档中...')
        if (await loadingHint.isVisible().catch(() => false)) {
          await loadingHint.waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => {})
        } else {
          await uiTab.waitForTimeout(250)
        }
        paginationLatencies.push(Date.now() - before)
        direction *= -1
      }
    } finally {
      await uiTab.close()
    }

    const e2eMs = await parsePromise
    expect(paginationLatencies.length).toBeGreaterThan(0)
    const maxLatency = Math.max(...paginationLatencies)

    // Perf BASELINE (not a hard gate). With real DashScope embedding, end-to-end time for a 200KB
    // doc is dominated by remote embedding of hundreds of chunks — the cleaning stage itself is cheap.
    // Per spec the ≤10s end-to-end target and the <500ms pagination target are warnings, not failures.
    // We record them as annotations and only hard-fail on a catastrophic UI hang.
    testInfo.annotations.push({ type: 'perf', description: `e2eMs=${e2eMs} (baseline target 10000ms)` })
    testInfo.annotations.push({
      type: 'perf',
      description: `paginationMaxMs=${maxLatency} (baseline target 500ms); samples=[${paginationLatencies.join(', ')}]`,
    })
    if (e2eMs > 10_000) {
      console.warn(`[ACC-10] WARN end-to-end ${e2eMs}ms exceeds 10s baseline (real DashScope embedding dominated).`)
    }
    if (maxLatency > 500) {
      console.warn(`[ACC-10] WARN pagination max ${maxLatency}ms exceeds 500ms baseline under embedding load.`)
    }
    expect(maxLatency, 'pagination must not catastrophically hang (>5s)').toBeLessThanOrEqual(5_000)

    const detail = await getDocument(request, headers, docId)
    const report = parseCleanReport(detail)
    expect(report?.cleanedLength).toBeGreaterThan(0)
    expect(report?.profile).toBeTruthy()

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '10')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(request, headers, kbId)
  }
})
