import { Link, useParams } from 'react-router'
import { useExecutionDetails } from '../api/hooks.ts'
import { useRetained } from '../api/useRetained.ts'
import { EmptyState } from '../components/EmptyState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { MissingResource } from '../components/MissingResource.tsx'
import { executionNotFound } from '../components/notFoundCopy.ts'
import { RequestError } from '../components/RequestError.tsx'
import { StableRegion } from '../components/StableRegion.tsx'
import { StatusBadge } from '../components/StatusBadge.tsx'
import { executionStatusNote } from '../lib/executionStatus.ts'
import { formatTimestamp, positiveId } from '../lib/format.ts'
import type { ExecutionStep } from '../types/stepHistory.ts'
import type { WorkflowExecution } from '../types/execution.ts'

export function ExecutionDetailPage() {
  const params = useParams()
  const executionId = positiveId(params.id ?? null)
  const details = useExecutionDetails(executionId)
  const retained = useRetained(details.state)

  if (executionId === null) {
    return (
      <StableRegion testId="execution-detail">
        <MissingExecution />
      </StableRegion>
    )
  }

  const execution = retained.data?.execution
  const steps = retained.data?.steps

  return (
    <StableRegion busy={retained.loading} testId="execution-detail">
      {retained.loading && !retained.data ? (
        <LoadingState label="Loading execution" />
      ) : null}
      {retained.loading && retained.data ? (
        <p className="sr-only" role="status">
          Loading execution
        </p>
      ) : null}
      {details.state.kind === 'error' ? (
        <RequestError
          status={details.state.status}
          message={details.state.message}
          onRetry={details.retry}
          notFound={executionNotFound}
          denied="You don't have access to this execution."
        />
      ) : null}
      {details.state.kind !== 'error' && execution && steps ? (
        <div className="mx-auto flex max-w-5xl flex-col gap-6">
          <ExecutionSummary execution={execution} onRefresh={details.retry} />
          <StepHistory steps={steps} />
        </div>
      ) : null}
    </StableRegion>
  )
}

function MissingExecution() {
  return <MissingResource {...executionNotFound} />
}

function ExecutionSummary({
  execution,
  onRefresh,
}: {
  execution: WorkflowExecution
  onRefresh: () => void
}) {
  const statusNote = executionStatusNote(execution.status)
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
      {statusNote ? (
        <p className="mt-4 text-sm leading-6 text-muted">{statusNote}</p>
      ) : null}
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
        to={`/workflows/${execution.workflowId}`}
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
          Lease until is informational. A time in the past does not prove the
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
              {step.stepType === 'CONDITION' && step.branchTaken === true ? (
                <p className="mt-4 text-sm text-ink">Took the true branch</p>
              ) : null}
              {step.stepType === 'CONDITION' && step.branchTaken === false ? (
                <p className="mt-4 text-sm text-ink">Took the false branch</p>
              ) : null}
              {step.status === 'SKIPPED' ? (
                <p className="mt-4 text-sm text-muted">This step was skipped.</p>
              ) : null}
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
