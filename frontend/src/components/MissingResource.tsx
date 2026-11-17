import { Link } from 'react-router'
import { EmptyState } from './EmptyState.tsx'

export function MissingResource({
  title,
  description,
  to,
  label,
}: {
  title: string
  description: string
  to: string
  label: string
}) {
  return (
    <EmptyState
      title={title}
      description={description}
      action={
        <Link
          to={to}
          className="rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
        >
          {label}
        </Link>
      }
    />
  )
}
