import { useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { SignInPanel } from '../auth/SignInPanel.tsx'
import { useSession } from '../auth/useSession.ts'
import { Header } from './Header.tsx'
import { LoadingState } from './LoadingState.tsx'
import { Sidebar } from './Sidebar.tsx'
import { UnsavedChanges } from './unsavedChanges.tsx'

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
  if (protectedRoute && session.status === 'checking') {
    main = <LoadingState label="Checking sign-in" />
  } else if (protectedRoute && session.status === 'out') {
    main = <SignInPanel />
  }

  return (
    <UnsavedChanges>
    <div className="h-svh overflow-hidden bg-navy-950 text-ink lg:grid lg:grid-cols-[16rem_minmax(0,1fr)]">
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />
      <div
        data-testid="app-content"
        className="flex h-full min-h-0 min-w-0 flex-col overflow-y-auto [overflow-anchor:none]"
      >
        <Header
          onMenu={() => setMenuOpen(true)}
          profile={session.status === 'in' ? session.profile : null}
        />
        <main
          data-testid="app-main"
          className="min-h-[calc(100svh-4.75rem)] flex-1 px-4 py-6 [overflow-anchor:none] sm:px-8 sm:py-8"
        >
          {main}
        </main>
      </div>
    </div>
    </UnsavedChanges>
  )
}
