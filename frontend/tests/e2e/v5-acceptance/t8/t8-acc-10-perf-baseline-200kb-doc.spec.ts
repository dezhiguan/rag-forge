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
    await createCleanProfile(kbId, {
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
      await uiTab.goto(`/knowledge/${kbId}/documents`, { waitUntil: 'domcontentloaded' })
      await uiTab.waitForLoadState('networkidle')

      const nextButton = uiTab.getByRole('button', { name: '下一页' })
      const prevButton = uiTab.getByRole('button', { name: '上一页' })

      await nextButton.waitFor({ state: 'visible', timeout: 20_000 })

      let direction = 1
      for (let step = 0; step < 8; step++) {
        const target = direction > 0 ? nextButton : prevButton
        const enabled = await target.isEnabled()
        if (!enabled) {
          direction *= -1
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
    expect.soft(e2eMs).toBeLessThanOrEqual(10_000)

    expect(paginationLatencies.length).toBeGreaterThan(0)
    const maxLatency = Math.max(...paginationLatencies)
    expect(maxLatency).toBeLessThanOrEqual(500)

    const detail = await getDocument(request, headers, docId)
    const report = parseCleanReport(detail)
    expect(report?.cleanedLength).toBeGreaterThan(0)
    expect(report?.profile).toBeTruthy()

    await loginPage(page)
    await screenshotPair(page, testInfo, docId, kbId, '10')
  } finally {
    await cleanupKb(request, headers, kbId)
    await deleteCleanProfile(kbId)
  }
})
