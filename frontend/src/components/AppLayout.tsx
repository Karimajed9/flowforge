import { useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { SignInPanel } from '../auth/SignInPanel.tsx'
import { useSession } from '../auth/useSession.ts'
import { Header } from './Header.tsx'
import { LoadingState } from './LoadingState.tsx'
import { Sidebar } from './Sidebar.tsx'

function requiresAccessToken(pathname: string): boolean {
  return (
    pathname === '/' ||
    pathname === '/workflows' ||
    pathname.startsWith('/workflows/') ||
    pathname === '/executions' ||
    pathname.startsWith('/executions/')
  )
}

export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const session = useSession()
  const { pathname } = useLocation()
  const protectedRoute = requiresAccessToken(pathname)

  let main = <Outlet />
  if (protectedRoute && session === 'checking') {
    main = <LoadingState label="Checking sign-in" />
  } else if (protectedRoute && session === 'out') {
    main = <SignInPanel />
  }

  return (
    <div className="min-h-svh bg-navy-950 text-ink lg:grid lg:grid-cols-[16rem_minmax(0,1fr)]">
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />
      <div className="min-w-0">
        <Header
          onMenu={() => setMenuOpen(true)}
          signedIn={session === 'in'}
        />
        <main className="px-4 py-6 sm:px-8 sm:py-8">{main}</main>
      </div>
    </div>
  )
}
