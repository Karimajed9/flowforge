import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/errors.ts'
import { createWorkflow } from '../api/client.ts'
import { parseName } from '../lib/workflowForm.ts'
import { useAllowNextNavigation, useReportDirty } from './unsavedNavigation.ts'

const fieldClass =
  'w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink'

export function CreateWorkflowForm() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const allowNextNavigation = useAllowNextNavigation()
  useReportDirty('create-workflow', name !== '' || description !== '')

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedName = parseName(name)
    if (!parsedName.ok) {
      setError(parsedName.message)
      return
    }

    setSubmitting(true)
    setError(null)
    try {
      const created = await createWorkflow({
        name: parsedName.name,
        description: description.trim() === '' ? null : description.trim(),
      })
      allowNextNavigation()
      navigate(`/workflows/${created.id}`)
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : 'The workflow was not created',
      )
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={(event) => {
        void onSubmit(event)
      }}
      className="rounded-2xl border border-line bg-navy-900 p-5 sm:p-6"
    >
      <h2 className="text-lg font-semibold">New workflow</h2>
      <div className="mt-4 grid gap-4">
        <label className="block text-sm" htmlFor="workflow-name">
          Name
          <input
            id="workflow-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className={`${fieldClass} mt-2`}
          />
        </label>
        <label className="block text-sm" htmlFor="workflow-description">
          Description
          <textarea
            id="workflow-description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            className={`${fieldClass} mt-2`}
            rows={3}
          />
        </label>
      </div>
      {error ? (
        <p className="mt-4 text-sm text-red-200" role="alert">
          {error}
        </p>
      ) : null}
      <button
        type="submit"
        disabled={submitting}
        className="mt-4 rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400 disabled:opacity-50"
      >
        {submitting ? 'Creating' : 'Create workflow'}
      </button>
    </form>
  )
}
