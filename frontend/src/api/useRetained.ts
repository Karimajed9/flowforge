import type { LoadState } from './useLoad.ts'

export function useRetained<T>(state: LoadState<T>) {
  const data =
    state.kind === 'ready' || state.kind === 'loading' ? state.data : null
  return {
    data,
    loading: state.kind === 'loading',
  }
}
