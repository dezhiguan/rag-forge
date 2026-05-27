import { onUnmounted } from 'vue'
import { getDocumentStatus } from '../api/document'
import { isTerminal } from './useDocumentStatus'

const POLL_INTERVAL_MS = 2000

export function useDocumentPolling() {
  const timers = new Map()

  function stop(docId) {
    const timer = timers.get(docId)
    if (timer) {
      clearInterval(timer)
      timers.delete(docId)
    }
  }

  function stopAll() {
    timers.forEach((timer) => clearInterval(timer))
    timers.clear()
  }

  /**
   * @param {number} docId
   * @param {(status) => void | Promise<void>} onUpdate - called each poll tick
   * @param {() => void | Promise<void>} [onDone] - called when completed or failed
   */
  function start(docId, onUpdate, onDone) {
    stop(docId)

    const tick = async () => {
      try {
        const res = await getDocumentStatus(docId)
        const status = res.data
        await onUpdate(status)
        if (isTerminal(status?.parseStatus)) {
          stop(docId)
          if (onDone) await onDone(status)
        }
      } catch {
        stop(docId)
      }
    }

    tick()
    timers.set(docId, setInterval(tick, POLL_INTERVAL_MS))
  }

  onUnmounted(stopAll)

  return { start, stop, stopAll }
}
