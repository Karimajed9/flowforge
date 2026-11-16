import type { WorkflowStep } from '../types/workflow.ts'

export function StepConfigurationSummary({ step }: { step: WorkflowStep }) {
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
    return (
      <p className="mt-3 text-sm text-muted">
        {method} {url}
      </p>
    )
  }

  return null
}
