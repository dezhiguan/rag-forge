export function sha256(file, onProgress) {
  if (!file) {
    return Promise.reject(new Error('file is required'))
  }
  if (typeof Worker === 'undefined') {
    return Promise.reject(new Error('HASH_WORKER_UNAVAILABLE'))
  }

  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./file-hash.worker.js', import.meta.url), { type: 'module' })
    worker.onmessage = (event) => {
      const message = event.data
      if (message?.type === 'progress') {
        onProgress?.(message)
        return
      }
      if (message?.type === 'done') {
        worker.terminate()
        resolve(message.hex)
        return
      }
      if (message?.type === 'error') {
        worker.terminate()
        reject(new Error(message.message || 'HASH_FAILED'))
      }
    }
    worker.onerror = (event) => {
      worker.terminate()
      reject(new Error(event.message || 'HASH_FAILED'))
    }
    worker.postMessage({ file })
  })
}
