import { expect } from '@playwright/test'
import { type CleanReport } from './t8-common'

export function expectUnicodeNormalized(chunksText: string, fixtureText: string) {
  expect(chunksText).not.toMatch(/\u00a0/)
  expect(chunksText).not.toMatch(/\u200b/)
  expect(chunksText).not.toMatch(/\u3000/)
  expect(chunksText).not.toMatch(/\ufeff/)

  const normalizedFixture = fixtureText
    .normalize('NFKC')
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/[\x09\x0b\f ]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()

  normalizedFixture
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .forEach((line) => {
      expect(chunksText).toContain(line.trim())
    })

  const expected = [
    'ｆｕｌｌｗｉｄｔｈ'.normalize('NFKC'),
    '１２３'.normalize('NFKC'),
    '㈠'.normalize('NFKC'),
    '，'.normalize('NFKC'),
  ]

  expected.forEach((value) => {
    expect(chunksText).toContain(value)
  })
}

export function expectL1Normalized(report: CleanReport | null) {
  expect(report, 'clean report should exist').toBeTruthy()
  expect(report?.profile?.l1Enabled).toBe(true)
  expect((report?.originalLength ?? 0) - (report?.cleanedLength ?? 0)).toBeGreaterThan(0)
}

export function sumPiiHits(report: CleanReport | null): number {
  if (!report?.piiHits) return 0
  return Object.values(report.piiHits).reduce((sum, v) => sum + Number(v || 0), 0)
}

export function reasonCount(report: CleanReport | null, reason: string): number {
  return (report?.removedRegions || []).filter((r) => r.reason === reason).length
}

export function noRawPii(text: string, pii: { phone: string; idCard: string; email: string; bank: string }) {
  expect(text).not.toContain(pii.phone)
  expect(text).not.toContain(pii.idCard)
  expect(text).not.toContain(pii.email)
  expect(text).not.toContain(pii.bank)
}

export function countRemovedRegions(report: CleanReport | null): number {
  return (report?.removedRegions || []).length
}
