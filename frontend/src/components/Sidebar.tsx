import { Activity, LayoutDashboard, Workflow, X } from 'lucide-react'
import { NavLink } from 'react-router'

const links = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/workflows', label: 'Workflows', icon: Workflow, end: false },
  { to: '/executions', label: 'Executions', icon: Activity, end: false },
]

export function Sidebar({
  open,
  onClose,
}: {
  open: boolean
  onClose: () => void
}) {
  return (
    <>
      {open ? (
        <button
          type="button"
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
          aria-label="Close menu"
          onClick={onClose}
        />
      ) : null}
      <aside
        data-testid="app-sidebar"
        className={`${open ? 'flex' : 'hidden'} fixed inset-y-0 left-0 z-40 w-64 flex-col overflow-y-auto border-r border-line bg-navy-900 lg:static lg:flex lg:h-svh`}
      >
        <div className="flex items-center justify-between px-5 py-5">
          <div>
            <p className="text-xs font-semibold tracking-[0.16em] text-teal-400">
              FLOWFORGE
            </p>
            <p className="mt-1 text-sm text-muted">Workflow dashboard</p>
          </div>
          <button
            type="button"
            className="rounded-lg p-2 text-muted hover:bg-navy-800 hover:text-ink lg:hidden"
            aria-label="Close menu"
            onClick={onClose}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </div>
        <nav className="flex flex-col gap-1 px-3 pb-6" aria-label="Primary">
          {links.map((link) => {
            const Icon = link.icon
            return (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                onClick={onClose}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium ${
                    isActive
                      ? 'bg-navy-800 text-teal-300'
                      : 'text-muted hover:bg-navy-800 hover:text-ink'
                  }`
                }
              >
                <Icon size={18} aria-hidden="true" />
                {link.label}
              </NavLink>
            )
          })}
        </nav>
      </aside>
    </>
  )
}
