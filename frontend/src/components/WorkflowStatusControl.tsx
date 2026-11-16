import { useState } from 'react'
import { ApiError } from '../api/errors.ts'
import { updateWorkflowStatus } from '../api/client.ts'
import type { Workflow } from '../types/workflow.ts'

export function WorkflowStatusControl({
  workflow,
  onUpdated,
}: {
  workflow: Workflow
  onUpdated: (workflow: Workflow) => void
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const nextActive = !workflow.active

  async function changeStatus() {
    setPending(true)
    setError(null)
    setResult(null)
    try {
      const updated = await updateWorkflowStatus(workflow.id, nextActive)
      onUpdated(updated)
      setResult(updated.active ? 'Workflow is active' : 'Workflow is inactive')
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'The status was not changed',
      )
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="flex flex-col items-start gap-3">
      <button
        type="button"
        disabled={pending}
        onClick={() => {
          void changeStatus()
        }}
        className="rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-50"
      >
        {pending ? 'Updating' : nextActive ? 'Activate' : 'Deactivate'}
      </button>
      {result ? (
        <p className="text-sm text-teal-300" role="status">
          {result}
        </p>
      ) : null}
      {error ? (
        <p className="text-sm text-red-200" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}
