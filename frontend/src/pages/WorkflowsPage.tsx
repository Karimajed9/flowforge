import { Link } from 'react-router'
import { useWorkflowList } from '../api/hooks.ts'
import { CreateWorkflowForm } from '../components/CreateWorkflowForm.tsx'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { formatTimestamp } from '../lib/format.ts'

export function WorkflowsPage() {
  const workflows = useWorkflowList()

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <CreateWorkflowForm />
      {workflows.state.kind === 'loading' ? (
        <LoadingState label="Loading workflows" />
      ) : null}
      {workflows.state.kind === 'error' ? (
        <ErrorState message={workflows.state.message} onRetry={workflows.retry} />
      ) : null}
      {workflows.state.kind === 'ready' && workflows.state.data.length === 0 ? (
        <EmptyState
          title="No workflows yet"
          description="The workflow list is empty. Create one with a name, or open a workflow by its id from the address bar."
        />
      ) : null}
      {workflows.state.kind === 'ready' && workflows.state.data.length > 0 ? (
        <ul className="grid gap-4">
          {workflows.state.data.map((workflow) => (
            <li
              key={workflow.id}
              className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6"
            >
              <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <h2 className="text-lg font-semibold">
                    <Link
                      to={`/workflows/${workflow.id}`}
                      className="hover:text-teal-300"
                    >
                      {workflow.name}
                    </Link>
                  </h2>
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
                  <dt className="text-muted">Workflow id</dt>
                  <dd className="mt-1">{workflow.id}</dd>
                </div>
                <div>
                  <dt className="text-muted">Created</dt>
                  <dd className="mt-1">{formatTimestamp(workflow.createdAt)}</dd>
                </div>
              </dl>
              <div className="mt-5 flex flex-wrap gap-4">
                <Link
                  to={`/workflows/${workflow.id}`}
                  className="text-sm font-medium text-teal-300 hover:text-teal-400"
                >
                  Open workflow
                </Link>
                <Link
                  to={`/executions?workflowId=${workflow.id}`}
                  className="text-sm font-medium text-teal-300 hover:text-teal-400"
                >
                  View executions
                </Link>
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
