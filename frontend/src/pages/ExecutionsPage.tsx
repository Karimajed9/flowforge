import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useWorkflowHistory } from '../api/hooks.ts'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { StatusBadge } from '../components/StatusBadge.tsx'
import { formatTimestamp, pageIndex, positiveId } from '../lib/format.ts'
import type { PageResponse, WorkflowExecution } from '../types/execution.ts'
import type { Workflow } from '../types/workflow.ts'

export function ExecutionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const workflowParam = searchParams.get('workflowId')
  const workflowId = workflowParam === null ? null : positiveId(workflowParam)
  const page = pageIndex(searchParams.get('page'))
  const [draft, setDraft] = useState(workflowParam ?? '')
  const [draftSource, setDraftSource] = useState(workflowParam ?? '')
  const [invalidDraft, setInvalidDraft] = useState(false)
  const history = useWorkflowHistory(workflowId, page)

  if ((workflowParam ?? '') !== draftSource) {
    setDraftSource(workflowParam ?? '')
    setDraft(workflowParam ?? '')
    setInvalidDraft(false)
  }

  function showWorkflow(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextId = positiveId(draft.trim())
    if (nextId === null) {
      setInvalidDraft(true)
      return
    }
    setInvalidDraft(false)
    setSearchParams({ workflowId: String(nextId), page: '0' })
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <section className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6">
        <form onSubmit={showWorkflow} className="flex flex-col gap-4">
          <div>
            <label className="block text-sm font-medium" htmlFor="workflow-id">
              Workflow id
            </label>
            <p className="mt-2 text-sm leading-6 text-muted">
              There is no API that lists every execution. Enter a workflow id
              to load that workflow and its execution history.
            </p>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row">
            <input
              id="workflow-id"
              inputMode="numeric"
              value={draft}
              onChange={(event) => {
                setDraft(event.target.value)
                setInvalidDraft(false)
              }}
              className="w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink"
            />
            <button
              type="submit"
              className="rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
            >
              Show executions
            </button>
          </div>
          {invalidDraft ? (
            <p className="text-sm text-red-200" role="alert">
              Workflow id must be a positive number.
            </p>
          ) : null}
        </form>
      </section>

      {workflowParam !== null && workflowId === null ? (
        <ErrorState message="Workflow id must be a positive number." />
      ) : null}

      {workflowId === null && workflowParam === null ? (
        <EmptyState
          title="No execution list selected"
          description="Choose a workflow id. Execution history is available one workflow at a time."
        />
      ) : null}

      {workflowId !== null && history.state.kind === 'loading' ? (
        <LoadingState label="Loading executions" />
      ) : null}
      {workflowId !== null &&
      history.state.kind === 'error' &&
      history.state.status === 404 ? (
        <EmptyState
          title="Workflow not found"
          description={history.state.message}
          action={
            <button
              type="button"
              onClick={history.retry}
              className="rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
            >
              Refresh
            </button>
          }
        />
      ) : null}
      {workflowId !== null &&
      history.state.kind === 'error' &&
      history.state.status !== 404 ? (
        <ErrorState message={history.state.message} onRetry={history.retry} />
      ) : null}
      {workflowId !== null && history.state.kind === 'ready' ? (
        <WorkflowHistoryView
          workflow={history.state.data.workflow}
          page={history.state.data.executions}
          onPage={(nextPage) =>
            setSearchParams({
              workflowId: String(workflowId),
              page: String(nextPage),
            })
          }
        />
      ) : null}
    </div>
  )
}

function WorkflowHistoryView({
  workflow,
  page,
  onPage,
}: {
  workflow: Workflow
  page: PageResponse<WorkflowExecution>
  onPage: (page: number) => void
}) {
  return (
    <div className="flex flex-col gap-4">
      <section className="rounded-2xl border border-line bg-navy-900 p-5">
        <h2 className="text-lg font-semibold">{workflow.name}</h2>
        <p className="mt-2 text-sm text-muted">
          Workflow {workflow.id}
          {workflow.active ? ' · Active' : ' · Inactive'}
        </p>
      </section>
      {page.content.length === 0 ? (
        <EmptyState
          title="No executions for this workflow"
          description="This workflow has no recorded executions on the current page."
        />
      ) : (
        <ul className="grid gap-4">
          {page.content.map((execution) => (
            <li
              key={execution.id}
              className="rounded-2xl border border-line bg-navy-900 p-5"
            >
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2 className="text-lg font-semibold">
                    Execution {execution.id}
                  </h2>
                  <p className="mt-1 text-sm text-muted">
                    {formatTimestamp(execution.createdAt)}
                  </p>
                </div>
                <StatusBadge status={execution.status} />
              </div>
              {execution.failureReason ? (
                <p className="mt-4 text-sm leading-6 text-red-100">
                  {execution.failureReason}
                </p>
              ) : null}
              <Link
                to={`/executions/${execution.id}`}
                className="mt-4 inline-block text-sm font-medium text-teal-300 hover:text-teal-400"
              >
                Open details
              </Link>
            </li>
          ))}
        </ul>
      )}
      {page.totalPages > 1 ? (
        <div className="flex items-center justify-between gap-3">
          <button
            type="button"
            className="rounded-lg border border-line px-3 py-2 text-sm disabled:opacity-40"
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
            className="rounded-lg border border-line px-3 py-2 text-sm disabled:opacity-40"
            disabled={page.number + 1 >= page.totalPages}
            onClick={() => onPage(page.number + 1)}
          >
            Next
          </button>
        </div>
      ) : null}
    </div>
  )
}
