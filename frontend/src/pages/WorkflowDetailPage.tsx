import { useState } from 'react'
import { Link, useParams } from 'react-router'
import type { WorkflowDetail } from '../api/client.ts'
import { useWorkflowDetail } from '../api/hooks.ts'
import { useRetained } from '../api/useRetained.ts'
import { CreateStepForms } from '../components/CreateStepForms.tsx'
import { EditableStepList } from '../components/EditableStepList.tsx'
import { LoadingState } from '../components/LoadingState.tsx'
import { MissingResource } from '../components/MissingResource.tsx'
import { workflowNotFound } from '../components/notFoundCopy.ts'
import { RequestError } from '../components/RequestError.tsx'
import { RunWorkflowPanel } from '../components/RunWorkflowPanel.tsx'
import { StableRegion } from '../components/StableRegion.tsx'
import { WorkflowStatusControl } from '../components/WorkflowStatusControl.tsx'
import { formatTimestamp, positiveId } from '../lib/format.ts'
import type { Workflow, WorkflowStep } from '../types/workflow.ts'

export function WorkflowDetailPage() {
  const params = useParams()
  const workflowId = positiveId(params.id ?? null)
  const details = useWorkflowDetail(workflowId)
  const retained = useRetained(details.state)

  if (workflowId === null) {
    return (
      <StableRegion testId="workflow-detail">
        <MissingWorkflow />
      </StableRegion>
    )
  }

  return (
    <StableRegion busy={retained.loading} testId="workflow-detail">
      {retained.loading && !retained.data ? (
        <LoadingState label="Loading workflow" />
      ) : null}
      {retained.loading && retained.data ? (
        <p className="sr-only" role="status">
          Loading workflow
        </p>
      ) : null}
      {details.state.kind === 'error' ? (
        <RequestError
          status={details.state.status}
          message={details.state.message}
          onRetry={details.retry}
          notFound={workflowNotFound}
          denied="You don't have access to this workflow."
        />
      ) : null}
      {details.state.kind !== 'error' && retained.data ? (
        <WorkflowDetailContent detail={retained.data} onRefresh={details.retry} />
      ) : null}
    </StableRegion>
  )
}

function MissingWorkflow() {
  return <MissingResource {...workflowNotFound} />
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
  const [revision, setRevision] = useState(detail.workflow.stepRevision)

  if (seen !== detail) {
    setSeen(detail)
    setWorkflow(detail.workflow)
    setSteps(detail.steps)
    setRevision(detail.workflow.stepRevision)
  }

  function addStep(step: WorkflowStep) {
    setSteps((current) => {
      const occupied = current.some((item) => item.position === step.position)
      const shifted = occupied
        ? current.map((item) =>
            item.position >= step.position
              ? { ...item, position: item.position + 1 }
              : item,
          )
        : current
      return [...shifted, step].sort(
        (left, right) => left.position - right.position || left.id - right.id,
      )
    })
    setRevision((current) => current + 1)
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <WorkflowHeader
        workflow={workflow}
        stepCount={steps.length}
        onUpdated={setWorkflow}
        onRefresh={onRefresh}
      />
      <RunWorkflowPanel workflowId={workflow.id} active={workflow.active} />
      <EditableStepList
        workflowId={workflow.id}
        steps={steps}
        revision={revision}
        onChange={(nextSteps, nextRevision) => {
          setSteps(nextSteps)
          setRevision(nextRevision)
        }}
      />
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">Add a step</h2>
        <p className="text-sm leading-6 text-muted">
          New steps are used by later runs. A run that already started keeps
          the steps it began with.
        </p>
        <CreateStepForms
          workflowId={workflow.id}
          steps={steps}
          revision={revision}
          onCreated={addStep}
        />
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

