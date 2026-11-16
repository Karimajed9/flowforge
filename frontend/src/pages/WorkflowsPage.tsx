import { Link, useSearchParams } from 'react-router'
import { useWorkflowList } from '../api/hooks.ts'
import { CreateWorkflowForm } from '../components/CreateWorkflowForm.tsx'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { formatTimestamp, pageIndex } from '../lib/format.ts'
import type { PageResponse } from '../types/execution.ts'
import type { Workflow } from '../types/workflow.ts'

export function WorkflowsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = pageIndex(searchParams.get('page'))
  const workflows = useWorkflowList(page)

  function setPage(next: number) {
    const updated = new URLSearchParams(searchParams)
    if (next <= 0) {
      updated.delete('page')
    } else {
      updated.set('page', String(next))
    }
    setSearchParams(updated)
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={workflows.retry}
          className="rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800"
        >
          Refresh
        </button>
      </div>
      <CreateWorkflowForm />
      {workflows.state.kind === 'loading' ? (
        <LoadingState label="Loading workflows" />
      ) : null}
      {workflows.state.kind === 'error' ? (
        <ErrorState message={workflows.state.message} onRetry={workflows.retry} />
      ) : null}
      {workflows.state.kind === 'ready' ? (
        <WorkflowList page={workflows.state.data} onPage={setPage} />
      ) : null}
    </div>
  )
}

function WorkflowList({
  page,
  onPage,
}: {
  page: PageResponse<Workflow>
  onPage: (page: number) => void
}) {
  return (
    <>
      {page.totalElements === 0 ? (
        <EmptyState
          title="No workflows yet"
          description="The workflow list is empty. Create one with a name, or open a workflow by its id from the address bar."
        />
      ) : null}
      {page.totalElements > 0 && page.content.length === 0 ? (
        <EmptyState
          title="No workflows on this page"
          description="This page is outside the workflows that are available."
        />
      ) : null}
      {page.content.length > 0 ? (
        <ul className="grid gap-4">
          {page.content.map((workflow) => (
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
      {page.totalPages > 1 ? (
        <div className="flex items-center justify-between gap-3">
          <button
            type="button"
            className="rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40"
            disabled={page.number <= 0}
            onClick={() => onPage(page.number - 1)}
          >
            Previous
          </button>
          <p className="text-sm text-muted">
            Page {page.number + 1} of {page.totalPages}
          </p>
          <button
            type="button"
            className="rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40"
            disabled={page.number + 1 >= page.totalPages}
            onClick={() => onPage(page.number + 1)}
          >
            Next
          </button>
        </div>
      ) : null}
    </>
  )
}
