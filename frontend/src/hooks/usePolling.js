import { useEffect } from 'react'

/**
 * Poll `fetchFn` every `intervalMs`, calling `onData` with each successful result — fires once
 * immediately, then on the interval, until unmounted or `deps` change (same contract as
 * `useEffect`'s own dependency array; this hook doesn't second-guess it).
 *
 * Pass `null` for `fetchFn` to stop polling (e.g. once a "nothing left to check" condition is
 * reached) without unmounting the caller — the effect cleans up and does nothing until a
 * dependency change brings a real `fetchFn` back.
 *
 * Centralises the interval + cleanup + "is this result still relevant" pattern around the
 * `getModulePrefetchStatus` poll (V3-5.4) that `RoadmapDetail.jsx` uses to reflect background
 * module drafting — a failed poll is silently retried next tick, since this is a best-effort
 * background status check, never a user-facing error.
 */
export function usePolling(fetchFn, intervalMs, onData, deps) {
  useEffect(() => {
    if (!fetchFn) return undefined
    let alive = true
    const tick = () => {
      fetchFn()
        .then((data) => {
          if (alive) onData(data)
        })
        .catch(() => {
          // Best-effort status only — a failed poll just tries again next tick.
        })
    }
    tick()
    const timer = setInterval(tick, intervalMs)
    return () => {
      alive = false
      clearInterval(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
}
