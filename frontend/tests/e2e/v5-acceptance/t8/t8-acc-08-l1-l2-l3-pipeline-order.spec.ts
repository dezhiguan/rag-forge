import {
  test,
  expect,
  asset,
  createCleanProfile,
  createKb,
  deleteCleanProfile,
  getChunks,
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
import { noRawPii, reasonCount, sumPiiHits } from '../_helpers/t8-asserts'

test.describe.configure({ timeout: 240_000 })

test('T8 ACC-08 L1 L2 L3 pipeline order', async ({ page, request }, testInfo) => {
  const headers = await login(request)
  const fullKb = await createKb(request, headers, 't8-acc-08-full')
  const skipKb = await createKb(request, headers, 't8-acc-08-skip')
  try {
    await createCleanProfile(fullKb, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'MASK',
    })
    await createCleanProfile(skipKb, {
      skipClean: true,
      l1Enabled: false,
      l2Enabled: false,
      l3Enabled: false,
    })

    const docFull = await uploadFile(request, headers, fullKb, asset(T8_FIXTURES.mixed))
    const docSkip = await uploadFile(request, headers, skipKb, asset(T8_FIXTURES.mixed))

    await Promise.all([
      waitForStatus(request, headers, docFull, 'COMPLETED', 240_000),
      waitForStatus(request, headers, docSkip, 'COMPLETED', 240_000),
    ])

    const fullDetail = await getDocument(request, headers, docFull)
    const fullReport = parseCleanReport(fullDetail)
    const skipDetail = await getDocument(request, headers, docSkip)

    const fullChunks = await getChunks(request, headers, docFull)
    const skipChunks = await getChunks(request, headers, docSkip)

    expect(fullChunks.length).toBeLessThan(skipChunks.length)
    expect((fullDetail.chunkCount ?? fullChunks.length)).toBeLessThanOrEqual(skipDetail.chunkCount ?? skipChunks.length)

    const fullText = fullChunks.map((chunk) => String(chunk.content || '')).join('\n')
    expect(fullText).not.toContain('13812345678')
    expect(fullText).not.toContain('保密 内部资料')

    expect(fullReport?.profile?.l1Enabled).toBe(true)
    expect(fullReport?.profile?.l2Enabled).toBe(true)
    expect(fullReport?.profile?.l3Enabled).toBe(true)
    expect(sumPiiHits(fullReport)).toBeGreaterThan(0)
    expect(fullReport?.cleanedLength).toBeLessThanOrEqual(fullReport?.originalLength ?? 0)
    expect(
      (reasonCount(fullReport, 'REPEATED_HEADER_FOOTER') +
        reasonCount(fullReport, 'WATERMARK') +
        reasonCount(fullReport, 'TOC')) >
        0,
    ).toBeTruthy()
    expect(
      (fullReport?.cleanedLength ?? 0) <
        ((fullReport?.originalLength ?? 0) + (fullReport?.removedRegions?.length || 0)),
    ).toBeTruthy()
    noRawPii(fullText, {
      phone: '13812345678',
      idCard: '440103199001011236',
      email: 'alice@example.com',
      bank: '6222 0202 0001 2345',
    })

    await loginPage(page)
    await screenshotPair(page, testInfo, docFull, fullKb, '08-full')
    await screenshotPair(page, testInfo, docSkip, skipKb, '08-skip')
  } finally {
    await cleanupKb(request, headers, fullKb)
    await cleanupKb(request, headers, skipKb)
    await deleteCleanProfile(fullKb)
    await deleteCleanProfile(skipKb)
  }
})
