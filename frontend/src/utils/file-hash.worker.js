export async function hashFile(input) {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('HASH_NOT_SUPPORTED')
  }
  const buffer = input instanceof ArrayBuffer
    ? input
    : await input.arrayBuffer()
  self.postMessage?.({ type: 'progress', loaded: 0, total: buffer.byteLength })
  const hash = await crypto.subtle.digest('SHA-256', buffer)
  self.postMessage?.({ type: 'progress', loaded: buffer.byteLength, total: buffer.byteLength })
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

if (typeof self !== 'undefined' && typeof self.addEventListener === 'function') {
  self.addEventListener('message', async (event) => {
    try {
      const hex = await hashFile(event.data.buffer)
      self.postMessage({ type: 'done', hex })
    } catch (error) {
      self.postMessage({ type: 'error', message: error?.message || 'HASH_FAILED' })
    }
  })
}
