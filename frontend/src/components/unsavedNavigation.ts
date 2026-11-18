import { createContext, useContext, useEffect } from 'react'

export const UNSAVED_NAVIGATION = 'Unsaved changes will be lost.'

export type UnsavedApi = {
  setDirty: (key: string, dirty: boolean) => void
  allowNextNavigation: () => void
}

export const UnsavedContext = createContext<UnsavedApi | null>(null)

export function useReportDirty(key: string, dirty: boolean) {
  const api = useContext(UnsavedContext)
  useEffect(() => {
    if (!api) {
      return
    }
    api.setDirty(key, dirty)
    return () => api.setDirty(key, false)
  }, [api, key, dirty])
}

export function useAllowNextNavigation(): () => void {
  const api = useContext(UnsavedContext)
  return api?.allowNextNavigation ?? (() => undefined)
}
