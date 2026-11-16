import { Link } from 'react-router'
import { useWorkflowList } from '../api/hooks.ts'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { StatusBadge } from '../components/StatusBadge.tsx'
import { EXECUTION_STATUSES } from '../types/execution.ts'

export function OverviewPage() {
  const workflows = useWorkflowList()

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <section className="rounded-2xl border border-line bg-navy-900 p-6 sm:p-8">
        <p className="text-sm font-medium text-teal-300">Dashboard</p>
        <h2 className="mt-2 text-2xl font-semibold">Workflow activity</h2>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-muted">
          Review workflows, their steps, and the executions recorded for each
          one. Recovery and abandonment stay outside this dashboard.
        </p>
        <div className="mt-6 flex flex-col gap-3 sm:flex-row">
          <Link
            to="/workflows"
            className="rounded-lg bg-teal-500 px-4 py-2 text-center text-sm font-semibold text-navy-950 hover:bg-teal-400"
          >
            View workflows
          </Link>
          <Link
            to="/executions"
            className="rounded-lg border border-line px-4 py-2 text-center text-sm font-semibold text-ink hover:bg-navy-800"
          >
            View executions
          </Link>
        </div>
      </section>

      {workflows.state.kind === 'loading' ? (
        <LoadingState label="Loading workflows" />
      ) : null}
      {workflows.state.kind === 'error' ? (
        <ErrorState message={workflows.state.message} onRetry={workflows.retry} />
      ) : null}
      {workflows.state.kind === 'ready' && workflows.state.data.length === 0 ? (
        <EmptyState
          title="No workflows yet"
          description="GET /api/workflows returned an empty list. Executions are stored per workflow, so the executions page stays empty until a workflow exists."
        />
      ) : null}
      {workflows.state.kind === 'ready' && workflows.state.data.length > 0 ? (
        <section className="rounded-2xl border border-line bg-navy-900 p-6">
          <p className="text-sm text-muted">Workflows</p>
          <p className="mt-2 text-3xl font-semibold">
            {workflows.state.data.length}
          </p>
          <Link
            to="/workflows"
            className="mt-4 inline-block text-sm font-medium text-teal-300 hover:text-teal-400"
          >
            Open the list
          </Link>
        </section>
      ) : null}

      <section className="rounded-2xl border border-line bg-navy-900 p-6">
        <h2 className="text-lg font-semibold">Execution statuses</h2>
        <p className="mt-2 text-sm leading-6 text-muted">
          These are the execution statuses returned by the API.
        </p>
        <div className="mt-4 flex flex-wrap gap-2">
          {EXECUTION_STATUSES.map((status) => (
            <StatusBadge key={status} status={status} />
          ))}
        </div>
      </section>
    </div>
  )
}
