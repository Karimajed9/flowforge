import { useEffect, useState } from 'react'
import { loadProfile, subscribe, type AuthProfile } from './session.ts'

export type SessionState =
  | { status: 'checking' }
  | { status: 'out' }
  | { status: 'in'; profile: AuthProfile }

export function useSession(): SessionState {
  const [session, setSession] = useState<SessionState>(
    typeof window === 'undefined' ? { status: 'out' } : { status: 'checking' },
  )

  useEffect(() => {
    let active = true
    const refresh = () => {
      void loadProfile()
        .then((profile) => {
          if (!active) {
            return
          }
          setSession(profile ? { status: 'in', profile } : { status: 'out' })
        })
        .catch(() => {
          if (active) {
            setSession({ status: 'out' })
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
