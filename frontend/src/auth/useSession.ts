import { useEffect, useState } from 'react'
import { getAccessToken, subscribe } from './session.ts'

export type SessionState = 'checking' | 'in' | 'out'

export function useSession(): SessionState {
  const [session, setSession] = useState<SessionState>(
    typeof window === 'undefined' ? 'out' : 'checking',
  )

  useEffect(() => {
    let active = true
    const refresh = () => {
      void getAccessToken().then((token) => {
        if (active) {
          setSession(token ? 'in' : 'out')
        }
      })
    }
    refresh()
    const unsubscribe = subscribe(refresh)
    return () => {
      active = false
      unsubscribe()
    }
  }, [])

  return session
}
