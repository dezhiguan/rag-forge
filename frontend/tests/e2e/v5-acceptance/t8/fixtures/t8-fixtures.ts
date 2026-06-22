import fs from 'node:fs'
import path from 'node:path'
import { asset } from '../../_helpers/t8-common'

export const T8_FIXTURES = {
  unicode: 'clean-unicode-zerowidth.txt',
  noisy: 'clean-noisy-header-footer.txt',
  pii: 'clean-pii-zh.txt',
  toc: 'clean-toc-watermark.txt',
  mixed: 'clean-mixed-everything.txt',
  pure: 'clean-pure-content.txt',
  emptyAfterStrip: 'clean-empty-after-strip.txt',
  perf: 'clean-perf-200kb.txt',
}

export const PII = {
  phone: '13812345678',
  altPhone: '13987654321',
  idCard: '440103199001011234',
  altIdCard: '520102198812123456',
  email: 'alice@example.com',
  altEmail: 'bob.brown@ragforge.example.net',
  bank: '6222 0202 0001 2345',
  altBank: '6250 1234 5678 9012',
}

export function fixturePath(name: string) {
  return asset(name)
}

export function fixtureText(name: string) {
  return fs.readFileSync(path.resolve(fixturePath(name)), 'utf8')
}
