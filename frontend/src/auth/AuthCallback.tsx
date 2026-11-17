import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { completeSignIn } from './session.ts'

export function AuthCallback() {
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    completeSignIn().then(
      () => {
        if (active) {
          window.location.replace('/')
        }
      },
      () => {
        if (active) {
          setFailed(true)
        }
      },
    )
    return () => {
      active = false
    }
  }, [])

  return (
    <main className="min-h-svh bg-navy-950 px-6 py-16 text-ink">
      <div className="mx-auto max-w-lg rounded-2xl border border-line bg-navy-900 px-6 py-14 text-center">
        <p className="text-xs font-semibold tracking-[0.14em] text-teal-400">
          FLOWFORGE
        </p>
        <h1 className="mt-3 text-lg font-semibold">
          {failed ? 'Sign-in failed' : 'Signing in'}
        </h1>
        {failed ? (
          <Link
            to="/"
            className="mt-6 inline-block rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
          >
            Back to FlowForge
          </Link>
        ) : (
          <p className="mt-2 text-sm text-muted">Waiting for the identity provider.</p>
        )}
      </div>
    </main>
  )
}
