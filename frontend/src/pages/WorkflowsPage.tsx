import { Link } from 'react-router'
import { useWorkflowList } from '../api/hooks.ts'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { formatTimestamp } from '../lib/format.ts'

export function WorkflowsPage() {
  const workflows = useWorkflowList()

  if (workflows.state.kind === 'loading') {
    return <LoadingState label="Loading workflows" />
  }

  if (workflows.state.kind === 'error') {
    return (
      <ErrorState message={workflows.state.message} onRetry={workflows.retry} />
    )
  }

  if (workflows.state.kind !== 'ready') {
    return null
  }

  if (workflows.state.data.length === 0) {
    return (
      <EmptyState
        title="No workflows yet"
        description="The workflow list is empty. This page reads GET /api/workflows and does not create or edit workflows."
      />
    )
  }

  return (
    <ul className="mx-auto grid max-w-5xl gap-4">
      {workflows.state.data.map((workflow) => (
        <li
          key={workflow.id}
          className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6"
        >
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <h2 className="text-lg font-semibold">{workflow.name}</h2>
              <p className="mt-2 text-sm leading-6 text-muted">
                {workflow.description?.trim()
                  ? workflow.description
                  : 'No description'}
              </p>
            </div>
            <span className="w-fit rounded-full bg-navy-800 px-2.5 py-1 text-xs font-semibold text-teal-300 ring-1 ring-line">
              {workflow.active ? 'Active' : 'Inactive'}
            </span>
          </div>
          <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-muted">Created</dt>
              <dd className="mt-1">{formatTimestamp(workflow.createdAt)}</dd>
            </div>
            <div>
              <dt className="text-muted">Workflow id</dt>
              <dd className="mt-1">{workflow.id}</dd>
            </div>
          </dl>
          <Link
            to={`/executions?workflowId=${workflow.id}`}
            className="mt-5 inline-block text-sm font-medium text-teal-300 hover:text-teal-400"
          >
            View executions
          </Link>
        </li>
      ))}
    </ul>
  )
}
