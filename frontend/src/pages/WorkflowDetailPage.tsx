import { useState } from 'react'
import { Link, useParams } from 'react-router'
import type { WorkflowDetail } from '../api/client.ts'
import { useWorkflowDetail } from '../api/hooks.ts'
import { CreateStepForms } from '../components/CreateStepForms.tsx'
import { EmptyState } from '../components/EmptyState.tsx'
import { ErrorState } from '../components/ErrorState.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { StepConfigurationSummary } from '../components/StepConfigurationSummary.tsx'
import { WorkflowStatusControl } from '../components/WorkflowStatusControl.tsx'
import { formatTimestamp, positiveId } from '../lib/format.ts'
import type { Workflow, WorkflowStep } from '../types/workflow.ts'

export function WorkflowDetailPage() {
  const params = useParams()
  const workflowId = positiveId(params.id ?? null)
  const details = useWorkflowDetail(workflowId)

  if (workflowId === null) {
    return <ErrorState message="Workflow id must be a positive number." />
  }

  if (details.state.kind === 'loading') {
    return <LoadingState label="Loading workflow" />
  }

  if (details.state.kind === 'error' && details.state.status === 404) {
    return (
      <EmptyState
        title="Workflow not found"
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

  return (
    <WorkflowDetailContent
      detail={details.state.data}
      onRefresh={details.retry}
    />
  )
}

function WorkflowDetailContent({
  detail,
  onRefresh,
}: {
  detail: WorkflowDetail
  onRefresh: () => void
}) {
  const [seen, setSeen] = useState(detail)
  const [workflow, setWorkflow] = useState(detail.workflow)
  const [steps, setSteps] = useState(detail.steps)

  if (seen !== detail) {
    setSeen(detail)
    setWorkflow(detail.workflow)
    setSteps(detail.steps)
  }

  function addStep(step: WorkflowStep) {
    setSteps((current) =>
      [...current, step].sort(
        (left, right) => left.position - right.position || left.id - right.id,
      ),
    )
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <WorkflowHeader
        workflow={workflow}
        stepCount={steps.length}
        onUpdated={setWorkflow}
        onRefresh={onRefresh}
      />
      <StepList steps={steps} />
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">Add a step</h2>
        <p className="text-sm leading-6 text-muted">
          Steps can be added. Editing, deleting, and reordering are not
          available.
        </p>
        <CreateStepForms workflowId={workflow.id} onCreated={addStep} />
      </section>
    </div>
  )
}

function WorkflowHeader({
  workflow,
  stepCount,
  onUpdated,
  onRefresh,
}: {
  workflow: Workflow
  stepCount: number
  onUpdated: (workflow: Workflow) => void
  onRefresh: () => void
}) {
  return (
    <section className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-2xl font-semibold">{workflow.name}</h2>
          <p className="mt-2 text-sm leading-6 text-muted">
            {workflow.description?.trim() ? workflow.description : 'No description'}
          </p>
        </div>
        <span className="w-fit rounded-full bg-navy-800 px-2.5 py-1 text-xs font-semibold text-teal-300 ring-1 ring-line">
          {workflow.active ? 'Active' : 'Inactive'}
        </span>
      </div>
      <dl className="mt-6 grid gap-4 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-muted">Workflow id</dt>
          <dd className="mt-1">{workflow.id}</dd>
        </div>
        <div>
          <dt className="text-muted">Step count</dt>
          <dd className="mt-1">{stepCount}</dd>
        </div>
        <div>
          <dt className="text-muted">Created</dt>
          <dd className="mt-1">{formatTimestamp(workflow.createdAt)}</dd>
        </div>
      </dl>
      <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <WorkflowStatusControl workflow={workflow} onUpdated={onUpdated} />
        <div className="flex flex-wrap gap-4">
          <Link
            to={`/executions?workflowId=${workflow.id}`}
            className="text-sm font-medium text-teal-300 hover:text-teal-400"
          >
            View executions
          </Link>
          <button
            type="button"
            onClick={onRefresh}
            className="text-sm font-medium text-teal-300 hover:text-teal-400"
          >
            Refresh
          </button>
        </div>
      </div>
    </section>
  )
}

function StepList({ steps }: { steps: WorkflowStep[] }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold">Steps</h2>
      {steps.length === 0 ? (
        <EmptyState
          title="No steps yet"
          description="This workflow has no steps. Add a field check or an HTTPS GET below."
        />
      ) : (
        <ol className="grid gap-4">
          {steps.map((step) => (
            <li
              key={step.id}
              className="rounded-2xl border border-line bg-navy-900 p-5"
            >
              <p className="text-xs font-semibold tracking-wide text-muted">
                Position {step.position}
              </p>
              <h3 className="mt-1 text-lg font-semibold">{step.name}</h3>
              <p className="mt-1 text-sm text-muted">{step.stepType}</p>
              <StepConfigurationSummary step={step} />
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
