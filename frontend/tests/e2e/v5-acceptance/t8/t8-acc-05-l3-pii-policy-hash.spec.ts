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
import { T8_FIXTURES, PII as PII_FIXTURES } from './fixtures/t8-fixtures'
import { noRawPii as assertNoRawPii, sumPiiHits } from '../_helpers/t8-asserts'

test.describe.configure({ timeout: 720_000 })

test('T8 ACC-05 L3 pii policy hash', async ({ page, request }, testInfo) => {
  const headers = await login(request)

  const kbIdA = await createKb(request, headers, 't8-acc-05-hash-a')
  const kbIdB = await createKb(request, headers, 't8-acc-05-hash-b')
  try {
    await createCleanProfile(request, headers, kbIdA, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'HASH',
    })
    await createCleanProfile(request, headers, kbIdB, {
      l1Enabled: true,
      l2Enabled: true,
      l3Enabled: true,
      piiPolicy: 'HASH',
    })

    const docA = await uploadFile(request, headers, kbIdA, asset(T8_FIXTURES.pii))
    await waitForStatus(request, headers, docA, 'COMPLETED', 600_000)

    const docB = await uploadFile(request, headers, kbIdB, asset(T8_FIXTURES.pii))
    await waitForStatus(request, headers, docB, 'COMPLETED', 600_000)

    const chunksA = (await getChunks(request, headers, docA)).map((c) => String(c.content || '')).join('\n')
    const chunksB = (await getChunks(request, headers, docB)).map((c) => String(c.content || '')).join('\n')

    assertNoRawPii(chunksA, {
      phone: PII_FIXTURES.phone,
      idCard: PII_FIXTURES.idCard,
      email: PII_FIXTURES.email,
      bank: PII_FIXTURES.bank,
    })

    assertNoRawPii(chunksB, {
      phone: PII_FIXTURES.phone,
      idCard: PII_FIXTURES.idCard,
      email: PII_FIXTURES.email,
      bank: PII_FIXTURES.bank,
    })

    const tokenA = collectHashTokens(chunksA)
    const tokenB = collectHashTokens(chunksB)
    for (const key of Object.keys(tokenA)) {
      expect(tokenA[key].length).toBeGreaterThan(0)
      expect(tokenB[key]).toEqual(tokenA[key])
      tokenA[key].forEach((t) => expect(t).toMatch(/^[0-9a-f]{16}$/))
    }

    const detail = await getDocument(request, headers, docA)
    const report = parseCleanReport(detail)
    expect(sumPiiHits(report)).toBeGreaterThanOrEqual(4)

    await loginPage(page)
    await screenshotPair(page, testInfo, docA, kbIdA, '05-a')
    await screenshotPair(page, testInfo, docB, kbIdB, '05-b')
  } finally {
    await cleanupKb(request, headers, kbIdA)
    await cleanupKb(request, headers, kbIdB)
    await deleteCleanProfile(request, headers, kbIdA)
    await deleteCleanProfile(request, headers, kbIdB)
  }
})

function collectHashTokens(text: string): Record<string, string[]> {
  const re = /(phone|email|idCard|bankCard)#([0-9a-f]{16})/g
  const output: Record<string, string[]> = { phone: [], email: [], idCard: [], bankCard: [] }
  for (const match of text.matchAll(re)) {
    const key = match[1]
    const hash = match[2]
    if (!output[key]?.includes(hash)) {
      output[key].push(hash)
    }
  }
  return output
}
