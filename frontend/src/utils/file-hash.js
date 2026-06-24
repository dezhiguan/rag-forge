export function sha256(file, onProgress) {
  if (!file) {
    return Promise.reject(new Error('file is required'))
  }
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    return Promise.reject(new Error('HASH_NOT_SUPPORTED'))
  }

  return file.arrayBuffer().then((buffer) => {
    onProgress?.({ loaded: 0, total: buffer.byteLength })

    if (typeof Worker === 'undefined') {
      return digestBuffer(buffer, onProgress)
    }

    return digestViaWorker(buffer, onProgress).catch(() => digestBuffer(buffer, onProgress))
  })
}

async function digestBuffer(buffer, onProgress) {
  onProgress?.({ loaded: buffer.byteLength, total: buffer.byteLength })
  const hash = await crypto.subtle.digest('SHA-256', buffer)
  return toHex(hash)
}

function digestViaWorker(buffer, onProgress) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./file-hash.worker.js', import.meta.url), { type: 'module' })
    let settled = false

    const finish = (fn) => (value) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      worker.terminate()
      fn(value)
    }

    const timer = setTimeout(() => {
      worker.terminate()
      reject(new Error('HASH_WORKER_TIMEOUT'))
    }, 30_000)

    worker.onmessage = (event) => {
      const message = event.data
      if (message?.type === 'progress') {
        onProgress?.(message)
        return
      }
      if (message?.type === 'done') {
        finish(resolve)(message.hex)
        return
      }
      if (message?.type === 'error') {
        finish(reject)(new Error(message.message || 'HASH_FAILED'))
      }
    }
    worker.onerror = (event) => {
      finish(reject)(new Error(event.message || 'HASH_FAILED'))
    }

    // Copy buffer so main thread can still fall back if the worker fails.
    worker.postMessage({ buffer: buffer.slice(0) })
  })
}

function toHex(hash) {
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}
