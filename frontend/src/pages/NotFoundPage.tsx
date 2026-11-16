import { Link } from 'react-router'
import { EmptyState } from '../components/EmptyState.tsx'

export function NotFoundPage() {
  return (
    <EmptyState
      title="Page not found"
      description="That address is not part of the dashboard."
      action={
        <Link
          to="/"
          className="rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
        >
          Back to overview
        </Link>
      }
    />
  )
}
