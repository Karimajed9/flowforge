import { useCallback, useEffect, useMemo, useRef } from 'react'
import type { ReactNode } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import { UNSAVED_NAVIGATION, UnsavedContext } from './unsavedNavigation.ts'

export function UnsavedChanges({ children }: { children: ReactNode }) {
  const keys = useRef(new Set<string>())
  const bypass = useRef(false)
  const deciding = useRef(false)

  const setDirty = useCallback((key: string, dirty: boolean) => {
    const present = keys.current.has(key)
    if (dirty === present) {
      return
    }
    if (dirty) {
      keys.current.add(key)
    } else {
      keys.current.delete(key)
    }
  }, [])

  const allowNextNavigation = useCallback(() => {
    bypass.current = true
    keys.current.clear()
  }, [])

  const api = useMemo(
    () => ({ setDirty, allowNextNavigation }),
    [setDirty, allowNextNavigation],
  )

  useBeforeUnload(
    useCallback((event: BeforeUnloadEvent) => {
      if (bypass.current || keys.current.size === 0) {
        return
      }
      event.preventDefault()
    }, []),
  )

  const blocker = useBlocker(
    useCallback(() => {
      if (bypass.current) {
        bypass.current = false
        return false
      }
      return keys.current.size > 0
    }, []),
  )

  useEffect(() => {
    if (blocker.state !== 'blocked') {
      deciding.current = false
      return
    }
    if (deciding.current) {
      return
    }
    deciding.current = true
    if (window.confirm(UNSAVED_NAVIGATION)) {
      blocker.proceed()
    } else {
      blocker.reset()
    }
  }, [blocker])

  return <UnsavedContext.Provider value={api}>{children}</UnsavedContext.Provider>
}
