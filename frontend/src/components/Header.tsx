import { Menu } from 'lucide-react'
import { useLocation } from 'react-router'
import { pageTitle } from '../lib/pageTitle.ts'

export function Header({ onMenu }: { onMenu: () => void }) {
  const { pathname } = useLocation()

  return (
    <header className="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-navy-950 px-4 py-4 sm:px-8">
      <button
        type="button"
        className="rounded-lg p-2 text-muted hover:bg-navy-800 hover:text-ink lg:hidden"
        aria-label="Open menu"
        onClick={onMenu}
      >
        <Menu size={18} aria-hidden="true" />
      </button>
      <div>
        <p className="text-xs font-semibold tracking-[0.14em] text-teal-400">
          FLOWFORGE
        </p>
        <h1 className="text-xl font-semibold">{pageTitle(pathname)}</h1>
      </div>
    </header>
  )
}
