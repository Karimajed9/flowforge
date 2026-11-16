import { useState } from 'react'
import { Outlet } from 'react-router'
import { Header } from './Header.tsx'
import { Sidebar } from './Sidebar.tsx'

export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="min-h-svh bg-navy-950 text-ink lg:grid lg:grid-cols-[16rem_minmax(0,1fr)]">
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />
      <div className="min-w-0">
        <Header onMenu={() => setMenuOpen(true)} />
        <main className="px-4 py-6 sm:px-8 sm:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
