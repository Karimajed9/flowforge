import { useState } from 'react'
import type { LoadState } from './useLoad.ts'

export function useRetained<T>(state: LoadState<T>) {
  const ready = state.kind === 'ready' ? state.data : null
  const [held, setHeld] = useState<T | null>(null)
  if (ready !== null && held !== ready) {
    setHeld(ready)
  }
  const loading = state.kind === 'loading'
  return {
    data: ready ?? (loading ? held : null),
    loading,
  }
}
