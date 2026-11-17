import { Menu } from 'lucide-react'
import { useLocation } from 'react-router'
import { isAdmin, signOut, type AuthProfile } from '../auth/session.ts'
import { pageTitle } from '../lib/pageTitle.ts'

export function Header({
  onMenu,
  profile = null,
}: {
  onMenu: () => void
  profile?: AuthProfile | null
}) {
  const { pathname } = useLocation()

  return (
    <header
      data-testid="app-header"
      className="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-navy-950 px-4 py-4 sm:px-8"
    >
      <button
        type="button"
        className="rounded-lg p-2 text-muted hover:bg-navy-800 hover:text-ink lg:hidden"
        aria-label="Open menu"
        onClick={onMenu}
      >
        <Menu size={18} aria-hidden="true" />
      </button>
      <div className="min-w-0 flex-1">
        <p className="text-xs font-semibold tracking-[0.14em] text-teal-400">
          FLOWFORGE
        </p>
        <h1 className="text-xl font-semibold">{pageTitle(pathname)}</h1>
      </div>
      {profile ? (
        <div className="flex items-center gap-3">
          <p className="text-sm text-muted">{profile.username}</p>
          {isAdmin(profile) ? (
            <p className="text-xs font-semibold tracking-wide text-teal-300">
              Administrator
            </p>
          ) : null}
          <button
            type="button"
            className="rounded-lg px-3 py-2 text-sm font-medium text-muted hover:bg-navy-800 hover:text-ink"
            onClick={() => void signOut()}
          >
            Sign out
          </button>
        </div>
      ) : null}
    </header>
  )
}
