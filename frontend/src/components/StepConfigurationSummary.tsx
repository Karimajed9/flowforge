import { formatConditionValue, readBranchIds } from '../lib/condition.ts'
import type { WorkflowStep } from '../types/workflow.ts'

export function StepConfigurationSummary({
  step,
  steps = [],
}: {
  step: WorkflowStep
  steps?: WorkflowStep[]
}) {
  if (step.stepType === 'VALIDATE_FIELDS') {
    const fields = Array.isArray(step.configuration.requiredFields)
      ? step.configuration.requiredFields.filter(
          (field): field is string => typeof field === 'string' && field.length > 0,
        )
      : []
    return <p className="mt-3 text-sm text-muted">Required fields: {fields.join(', ') || '—'}</p>
  }

  if (step.stepType === 'HTTP_REQUEST') {
    const method =
      typeof step.configuration.method === 'string' ? step.configuration.method : '—'
    const url = typeof step.configuration.url === 'string' ? step.configuration.url : '—'
    const source =
      step.configuration.bodySource === 'STATIC_JSON'
        ? 'Static JSON'
        : step.configuration.bodySource === 'WORKFLOW_PAYLOAD'
          ? 'Workflow payload'
          : null
    return (
      <div className="mt-3 text-sm text-muted">
        <p>
          {method} {url}
        </p>
        {method === 'POST' && source ? <p>{source}</p> : null}
      </div>
    )
  }

  if (step.stepType === 'CONDITION') {
    const operator =
      typeof step.configuration.operator === 'string' ? step.configuration.operator : 'CONDITION'
    const field = typeof step.configuration.field === 'string' ? step.configuration.field : ''
    const branches = readBranchIds(step.configuration)
    return (
      <div className="mt-3 text-sm text-muted">
        <p>
          {operator} {field}
        </p>
        {operator !== 'EXISTS' && Object.hasOwn(step.configuration, 'value') ? (
          <p>Value {formatConditionValue(step.configuration.value)}</p>
        ) : null}
        {branches ? (
          <>
            <BranchNames label="TRUE" ids={branches.trueSteps} steps={steps} />
            <BranchNames label="FALSE" ids={branches.falseSteps} steps={steps} />
          </>
        ) : (
          <p className="mt-3">Branch steps could not be read.</p>
        )}
      </div>
    )
  }

  return null
}

function BranchNames({
  label,
  ids,
  steps,
}: {
  label: string
  ids: number[]
  steps: WorkflowStep[]
}) {
  return (
    <div className="mt-3">
      <p className="text-xs font-semibold tracking-wide text-ink">{label}</p>
      {ids.length === 0 ? (
        <p className="mt-1">None</p>
      ) : (
        <ul className="mt-1 grid gap-1">
          {ids.map((id) => {
            const match = steps.find((item) => item.id === id)
            return <li key={id}>{match ? match.name : `Missing step ${id}`}</li>
          })}
        </ul>
      )}
    </div>
  )
}
