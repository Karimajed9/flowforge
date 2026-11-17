import { useState } from 'react'
import { signIn } from './session.ts'

export function SignInPanel() {
  const [error, setError] = useState<string | null>(null)

  function onSignIn() {
    setError(null)
    try {
      signIn()
    } catch {
      setError('The sign-in service could not be reached. Start it and try again.')
    }
  }

  return (
    <div className="rounded-2xl border border-line bg-navy-900 px-6 py-14 text-center">
      <h2 className="text-lg font-semibold">Sign in</h2>
      <p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-muted">
        Sign-in opens the identity provider. The browser keeps a session
        cookie, and the API keeps the tokens.
      </p>
      <button
        type="button"
        className="mt-6 rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
        onClick={onSignIn}
      >
        Sign in
      </button>
      {error ? (
        <p className="mt-4 text-sm text-red-200" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}
