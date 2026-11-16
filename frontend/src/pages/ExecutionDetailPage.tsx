import { Link, useParams } from 'react-router'
import { useExecutionDetails } from '../api/hooks.ts'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { StatusBadge } from '../components/StatusBadge.tsx'
import { formatTimestamp, positiveId } from '../lib/format.ts'
import type { ExecutionStep } from '../types/stepHistory.ts'
import type { WorkflowExecution } from '../types/execution.ts'

export function ExecutionDetailPage() {
  const params = useParams()
  const executionId = positiveId(params.id ?? null)
  const details = useExecutionDetails(executionId)

  if (executionId === null) {
    return <ErrorState message="Execution id must be a positive number." />
  }

  if (details.state.kind === 'loading') {
    return <LoadingState label="Loading execution" />
  }

  if (details.state.kind === 'error' && details.state.status === 404) {
    return (
      <EmptyState
        title="Execution not found"
        description={details.state.message}
        action={
          <button
            type="button"
            onClick={details.retry}
            className="rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400"
          >
            Refresh
          </button>
        }
      />
    )
  }

  if (details.state.kind === 'error') {
    return (
      <ErrorState message={details.state.message} onRetry={details.retry} />
    )
  }

  if (details.state.kind !== 'ready') {
    return null
  }

  const { execution, steps } = details.state.data

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <ExecutionSummary execution={execution} onRefresh={details.retry} />
      <StepHistory steps={steps} />
    </div>
  )
}

function ExecutionSummary({
  execution,
  onRefresh,
}: {
  execution: WorkflowExecution
  onRefresh: () => void
}) {
  return (
    <section className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-2xl font-semibold">Execution {execution.id}</h2>
          <p className="mt-2 text-sm text-muted">
            Workflow {execution.workflowId}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge status={execution.status} />
          <button
            type="button"
            onClick={onRefresh}
            className="rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800"
          >
            Refresh
          </button>
        </div>
      </div>
      <dl className="mt-6 grid gap-4 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted">Execution id</dt>
          <dd className="mt-1">{execution.id}</dd>
        </div>
        <div>
          <dt className="text-muted">Workflow id</dt>
          <dd className="mt-1">{execution.workflowId}</dd>
        </div>
        <div>
          <dt className="text-muted">Created</dt>
          <dd className="mt-1">{formatTimestamp(execution.createdAt)}</dd>
        </div>
        {execution.failureReason ? (
          <div>
            <dt className="text-muted">Failure reason</dt>
            <dd className="mt-1 text-red-100">{execution.failureReason}</dd>
          </div>
        ) : null}
      </dl>
      <Link
        to={`/executions?workflowId=${execution.workflowId}`}
        className="mt-6 inline-block text-sm font-medium text-teal-300 hover:text-teal-400"
      >
        Back to this workflow
      </Link>
    </section>
  )
}

function StepHistory({ steps }: { steps: ExecutionStep[] }) {
  return (
    <section className="flex flex-col gap-4">
      <div>
        <h2 className="text-lg font-semibold">Step history</h2>
        <p className="mt-2 text-sm leading-6 text-muted">
          leaseUntil is informational. A past timestamp does not prove the
          worker has stopped.
        </p>
      </div>
      {steps.length === 0 ? (
        <EmptyState
          title="No step history"
          description="This execution has no steps."
        />
      ) : (
        <ol className="grid gap-4">
          {steps.map((step) => (
            <li
              key={step.stepId}
              className="rounded-2xl border border-line bg-navy-900 p-5"
            >
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <p className="text-xs font-semibold tracking-wide text-muted">
                    Position {step.position}
                  </p>
                  <h3 className="mt-1 text-lg font-semibold">{step.name}</h3>
                  <p className="mt-1 text-sm text-muted">{step.stepType}</p>
                </div>
                <StatusBadge status={step.status} />
              </div>
              <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-muted">Attempt</dt>
                  <dd className="mt-1">{step.attemptNumber ?? '—'}</dd>
                </div>
                <div>
                  <dt className="text-muted">Started</dt>
                  <dd className="mt-1">{formatTimestamp(step.startedAt)}</dd>
                </div>
                <div>
                  <dt className="text-muted">Updated</dt>
                  <dd className="mt-1">{formatTimestamp(step.updatedAt)}</dd>
                </div>
                <div>
                  <dt className="text-muted">Lease until</dt>
                  <dd className="mt-1">{formatTimestamp(step.leaseUntil)}</dd>
                </div>
              </dl>
              {step.failureReason ? (
                <p className="mt-4 text-sm leading-6 text-red-100">
                  <span className="text-muted">Failure reason: </span>
                  {step.failureReason}
                </p>
              ) : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
