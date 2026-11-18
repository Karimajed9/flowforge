import { useEffect, useEffectEvent, useState } from 'react'
import { isAbort } from './errors.ts'

export type LoadState<T> =
  | { kind: 'idle' }
  | { kind: 'loading'; data: T | null }
  | { kind: 'ready'; data: T }
  | { kind: 'error'; message: string; status: number | null }

function errorStatus(error: unknown): number | null {
  if (
    error !== null &&
    typeof error === 'object' &&
    'status' in error &&
    typeof error.status === 'number'
  ) {
    return error.status
  }
  return null
}

export function useLoad<T>(
  key: string | null,
  load: (signal: AbortSignal) => Promise<T>,
) {
  const loadEvent = useEffectEvent(load)
  const [attempt, setAttempt] = useState(0)
  const requestKey = key === null ? null : `${key}#${attempt}`
  const [seenKey, setSeenKey] = useState(requestKey)
  const [state, setState] = useState<LoadState<T>>(
    requestKey === null ? { kind: 'idle' } : { kind: 'loading', data: null },
  )

  if (seenKey !== requestKey) {
    const previous =
      state.kind === 'ready' || state.kind === 'loading' ? state.data : null
    setSeenKey(requestKey)
    setState(
      requestKey === null
        ? { kind: 'idle' }
        : { kind: 'loading', data: previous },
    )
  }

  useEffect(() => {
    if (requestKey === null) {
      return
    }

    const controller = new AbortController()

    loadEvent(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) {
          setState({ kind: 'ready', data })
        }
      },
      (error: unknown) => {
        if (controller.signal.aborted || isAbort(error)) {
          return
        }
        const message =
          error instanceof Error ? error.message : 'Something went wrong'
        setState({ kind: 'error', message, status: errorStatus(error) })
      },
    )

    return () => controller.abort()
  }, [requestKey])

  return {
    state,
    retry: () => setAttempt((value) => value + 1),
  }
}
