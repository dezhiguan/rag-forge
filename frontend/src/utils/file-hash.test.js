import assert from 'node:assert/strict'
import { createHash, webcrypto } from 'node:crypto'
import test from 'node:test'
import { hashFile } from './file-hash.worker.js'

globalThis.crypto ??= webcrypto
globalThis.self = { postMessage: () => {} }

test('hashFile empty file', async () => {
  const file = new Blob([], { type: 'application/octet-stream' })
  const hex = await hashFile(file)
  assert.equal(hex, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
})

test('hashFile short text hello world', async () => {
  const file = new Blob([new TextEncoder().encode('hello world')])
  const hex = await hashFile(file)
  const expected = createHash('sha256').update('hello world').digest('hex')
  assert.equal(hex, expected)
  assert.equal(hex, 'b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9')
})

test('hashFile blob larger than 64 bytes', async () => {
  const data = new Uint8Array(200)
  for (let i = 0; i < data.length; i += 1) {
    data[i] = i
  }
  const file = new Blob([data])
  const hex = await hashFile(file)
  const expected = createHash('sha256').update(data).digest('hex')
  assert.equal(hex, expected)
})

test('hashFile 50MB+1 byte boundary', async () => {
  const size = 50 * 1024 * 1024 + 1
  const data = new Uint8Array(size)
  data[0] = 0xab
  data[size - 1] = 0xcd
  const file = new Blob([data])
  const hex = await hashFile(file)
  const expected = createHash('sha256').update(data).digest('hex')
  assert.equal(hex, expected)
})

test('hashFile throws HASH_NOT_SUPPORTED when crypto.subtle is missing', async () => {
  const originalSubtle = globalThis.crypto.subtle
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: undefined,
  })
  try {
    const file = new Blob([new TextEncoder().encode('x')])
    await assert.rejects(() => hashFile(file), /HASH_NOT_SUPPORTED/)
  } finally {
    Object.defineProperty(globalThis.crypto, 'subtle', {
      configurable: true,
      value: originalSubtle,
    })
  }
})
