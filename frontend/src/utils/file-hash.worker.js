export async function hashFile(file) {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('HASH_NOT_SUPPORTED')
  }
  self.postMessage({ type: 'progress', loaded: 0, total: file.size })
  const buf = await file.arrayBuffer()
  self.postMessage({ type: 'progress', loaded: file.size, total: file.size })
  const hash = await crypto.subtle.digest('SHA-256', buf)
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

if (typeof self !== 'undefined' && typeof self.addEventListener === 'function') {
  self.addEventListener('message', async (event) => {
    try {
      const hex = await hashFile(event.data.file)
      self.postMessage({ type: 'done', hex })
    } catch (error) {
      self.postMessage({ type: 'error', message: error?.message || 'HASH_FAILED' })
    }
  })
}
