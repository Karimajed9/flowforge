import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useWorkflowList } from '../api/hooks.ts'
import { useRetained } from '../api/useRetained.ts'
import { CreateWorkflowForm } from '../components/CreateWorkflowForm.tsx'
import { EmptyState } from '../components/EmptyState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { RequestError } from '../components/RequestError.tsx'
import { StableRegion } from '../components/StableRegion.tsx'
import { formatTimestamp, pageIndex } from '../lib/format.ts'
import type { PageResponse } from '../types/execution.ts'
import type { Workflow } from '../types/workflow.ts'

const SEARCH_DELAY_MS = 300

type StatusFilter = 'all' | 'active' | 'inactive'

function statusFilter(value: string | null): StatusFilter {
  if (value === 'active' || value === 'inactive') {
    return value
  }
  return 'all'
}

function activeFlag(status: StatusFilter): boolean | null {
  if (status === 'active') {
    return true
  }
  if (status === 'inactive') {
    return false
  }
  return null
}

export function WorkflowsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = pageIndex(searchParams.get('page'))
  const query = searchParams.get('q') ?? ''
  const status = statusFilter(searchParams.get('status'))
  const [draft, setDraft] = useState(query)
  const [draftSource, setDraftSource] = useState(query)
  const workflows = useWorkflowList(page, query, activeFlag(status))
  const retained = useRetained(workflows.state)

  if (query !== draftSource) {
    setDraftSource(query)
    setDraft(query)
  }

  useEffect(() => {
    const handle = window.setTimeout(() => {
      const next = draft.trim()
      if (next === query) {
        return
      }
      const updated = new URLSearchParams(searchParams)
      if (next === '') {
        updated.delete('q')
      } else {
        updated.set('q', next)
      }
      updated.delete('page')
      setSearchParams(updated, { replace: true })
    }, SEARCH_DELAY_MS)
    return () => window.clearTimeout(handle)
  }, [draft, query, searchParams, setSearchParams])

  function setPage(next: number) {
    const updated = new URLSearchParams(searchParams)
    if (next <= 0) {
      updated.delete('page')
    } else {
      updated.set('page', String(next))
    }
    setSearchParams(updated)
  }

  function setStatus(next: StatusFilter) {
    const updated = new URLSearchParams(searchParams)
    if (next === 'all') {
      updated.delete('status')
    } else {
      updated.set('status', next)
    }
    updated.delete('page')
    setSearchParams(updated)
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <SearchBar
        draft={draft}
        status={status}
        onDraft={setDraft}
        onStatus={setStatus}
      />
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
      <StableRegion busy={retained.loading} testId="workflow-results">
        {retained.loading && !retained.data ? (
          <LoadingState label="Loading workflows" />
        ) : null}
        {retained.loading && retained.data ? (
          <p className="sr-only" role="status">
            Loading workflows
          </p>
        ) : null}
        {workflows.state.kind === 'error' ? (
          <RequestError
            status={workflows.state.status}
            message={workflows.state.message}
            onRetry={workflows.retry}
            denied="You don't have access to these workflows."
          />
        ) : null}
        {workflows.state.kind !== 'error' && retained.data ? (
          <WorkflowList
            page={retained.data}
            filtered={query !== '' || status !== 'all'}
            onPage={setPage}
          />
        ) : null}
      </StableRegion>
    </div>
  )
}

function SearchBar({
  draft,
  status,
  onDraft,
  onStatus,
}: {
  draft: string
  status: StatusFilter
  onDraft: (value: string) => void
  onStatus: (status: StatusFilter) => void
}) {
  const filters: Array<{ value: StatusFilter; label: string }> = [
    { value: 'all', label: 'All' },
    { value: 'active', label: 'Active' },
    { value: 'inactive', label: 'Inactive' },
  ]
  return (
    <section
      data-testid="workflow-search-bar"
      className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6"
    >
      <label className="block text-sm font-medium" htmlFor="workflow-search">
        Search
      </label>
      <input
        id="workflow-search"
        type="search"
        value={draft}
        maxLength={150}
        placeholder="Name or workflow id"
        onChange={(event) => onDraft(event.target.value)}
        className="mt-3 w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink"
      />
      <div className="mt-4 flex flex-wrap gap-2" role="group" aria-label="Status">
        {filters.map((filter) => {
          const selected = status === filter.value
          return (
            <button
              key={filter.value}
              type="button"
              aria-pressed={selected}
              onClick={() => onStatus(filter.value)}
              className={
                selected
                  ? 'inline-flex h-10 items-center rounded-lg border border-teal-500 bg-teal-500 px-3 text-sm font-semibold text-navy-950'
                  : 'inline-flex h-10 items-center rounded-lg border border-line px-3 text-sm font-semibold text-ink hover:bg-navy-800'
              }
            >
              {filter.label}
            </button>
          )
        })}
      </div>
    </section>
  )
}

function WorkflowList({
  page,
  filtered,
  onPage,
}: {
  page: PageResponse<Workflow>
  filtered: boolean
  onPage: (page: number) => void
}) {
  return (
    <>
      {page.totalElements === 0 ? (
        <EmptyState
          title={filtered ? 'No matching workflows' : 'No workflows yet'}
          description={
            filtered
              ? 'Nothing matches this search. Try another name, id, or status.'
              : 'The workflow list is empty. Create one with a name, or open a workflow by its id from the address bar.'
          }
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
