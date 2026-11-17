import { Link, useSearchParams } from 'react-router'
import { useDashboardOverview, useRecentExecutions } from '../api/hooks.ts'
import { useRetained } from '../api/useRetained.ts'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { StableRegion } from '../components/StableRegion.tsx'
import { StatusBadge } from '../components/StatusBadge.tsx'
import { formatTimestamp, pageIndex } from '../lib/format.ts'
import type { DashboardOverview, RecentExecution } from '../types/dashboard.ts'
import type { PageResponse } from '../types/execution.ts'

export function OverviewPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = pageIndex(searchParams.get('page'))
  const overview = useDashboardOverview()
  const recent = useRecentExecutions(page)
  const overviewView = useRetained(overview.state)
  const recentView = useRetained(recent.state)

  function setPage(next: number) {
    const updated = new URLSearchParams(searchParams)
    if (next <= 0) {
      updated.delete('page')
    } else {
      updated.set('page', String(next))
    }
    setSearchParams(updated)
  }

  function refresh() {
    overview.retry()
    recent.retry()
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <section className="rounded-2xl border border-line bg-navy-900 p-6 sm:p-8">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <p className="text-sm font-medium text-teal-300">Dashboard</p>
            <h2 className="mt-2 text-2xl font-semibold">Workflow activity</h2>
            <p className="mt-3 max-w-2xl text-sm leading-6 text-muted">
              Counts come from the workflow and execution records. Success and
              failure counts use finished executions inside the reported window.
            </p>
          </div>
          <button
            type="button"
            onClick={refresh}
            className="rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800"
          >
            Refresh
          </button>
        </div>
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

      <StableRegion
        busy={overviewView.loading || recentView.loading}
        testId="overview-results"
      >
        {overviewView.loading && !overviewView.data ? (
          <LoadingState label="Loading overview" />
        ) : null}
        {overviewView.loading && overviewView.data ? (
          <p className="sr-only" role="status">
            Loading overview
          </p>
        ) : null}
        {overview.state.kind === 'error' ? (
          <ErrorState message={overview.state.message} onRetry={overview.retry} />
        ) : null}
        {overview.state.kind !== 'error' && overviewView.data ? (
          <OverviewStats overview={overviewView.data} />
        ) : null}

        {recentView.loading && !recentView.data ? (
          <LoadingState label="Loading recent executions" />
        ) : null}
        {recentView.loading && recentView.data ? (
          <p className="sr-only" role="status">
            Loading recent executions
          </p>
        ) : null}
        {recent.state.kind === 'error' ? (
          <ErrorState message={recent.state.message} onRetry={recent.retry} />
        ) : null}
        {recent.state.kind !== 'error' && recentView.data ? (
          <RecentExecutions page={recentView.data} onPage={setPage} />
        ) : null}
      </StableRegion>
    </div>
  )
}

function OverviewStats({ overview }: { overview: DashboardOverview }) {
  const windowLabel = `Last ${overview.windowDays} days`
  return (
    <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <Stat label="Total workflows" value={String(overview.totalWorkflows)} detail="All workflows" />
      <Stat
        label="Active workflows"
        value={String(overview.activeWorkflows)}
        detail="Currently active"
      />
      <Stat
        label="Total executions"
        value={String(overview.totalExecutions)}
        detail="All time"
      />
      <Stat
        label="Succeeded"
        value={String(overview.succeededExecutions)}
        detail={windowLabel}
      />
      <Stat
        label="Failed"
        value={String(overview.failedExecutions)}
        detail={windowLabel}
      />
      <Stat
        label="Success rate"
        value={overview.successRate == null ? '—' : `${Math.round(overview.successRate * 100)}%`}
        detail={
          overview.successRate == null
            ? `No finished executions in the last ${overview.windowDays} days`
            : windowLabel
        }
      />
    </section>
  )
}

function Stat({
  label,
  value,
  detail,
}: {
  label: string
  value: string
  detail: string
}) {
  return (
    <article className="rounded-2xl border border-line bg-navy-900 p-5">
      <p className="text-sm text-muted">{label}</p>
      <p className="mt-2 text-3xl font-semibold">{value}</p>
      <p className="mt-2 text-sm leading-6 text-muted">{detail}</p>
    </article>
  )
}

function RecentExecutions({
  page,
  onPage,
}: {
  page: PageResponse<RecentExecution>
  onPage: (page: number) => void
}) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold">Recent executions</h2>
      {page.totalElements === 0 ? (
        <EmptyState
          title="No executions yet"
          description="Execution counts stay at zero until a workflow records an execution."
        />
      ) : null}
      {page.totalElements > 0 && page.content.length === 0 ? (
        <EmptyState
          title="No executions on this page"
          description="This page is outside the recent executions that are available."
        />
      ) : null}
      {page.content.length > 0 ? (
        <ul className="grid gap-4">
          {page.content.map((execution) => (
            <li
              key={execution.id}
              className="rounded-2xl border border-line bg-navy-900 p-5"
            >
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h3 className="text-lg font-semibold">
                    <Link
                      to={`/workflows/${execution.workflowId}`}
                      className="hover:text-teal-300"
                    >
                      {execution.workflowName}
                    </Link>
                  </h3>
                  <p className="mt-1 text-sm text-muted">
                    Execution {execution.id} · {formatTimestamp(execution.createdAt)}
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
    </section>
  )
}
