import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/errors.ts'
import { createWorkflowStep } from '../api/client.ts'
import {
  buildHttpRequestConfiguration,
  parseName,
  parsePosition,
  parseRequiredFieldNames,
} from '../lib/workflowForm.ts'
import type { WorkflowStep } from '../types/workflow.ts'

const fieldClass =
  'w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink'

const POSITION_TAKEN = 'A step already uses this position. Choose another one.'

function addStepError(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The step was not added'
  }
  if (
    caught.status === 409 &&
    (caught.title === 'Workflow step position conflict' ||
      caught.message === 'A step already exists at this position')
  ) {
    return POSITION_TAKEN
  }
  return caught.message
}

export function CreateStepForms({
  workflowId,
  onCreated,
}: {
  workflowId: number
  onCreated: (step: WorkflowStep) => void
}) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <ValidateFieldsForm workflowId={workflowId} onCreated={onCreated} />
      <HttpGetForm workflowId={workflowId} onCreated={onCreated} />
    </div>
  )
}

function ValidateFieldsForm({
  workflowId,
  onCreated,
}: {
  workflowId: number
  onCreated: (step: WorkflowStep) => void
}) {
  const [name, setName] = useState('')
  const [position, setPosition] = useState('')
  const [fields, setFields] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedName = parseName(name)
    if (!parsedName.ok) {
      setError(parsedName.message)
      return
    }
    const parsedPosition = parsePosition(position)
    if (!parsedPosition.ok) {
      setError(parsedPosition.message)
      return
    }
    const parsedFields = parseRequiredFieldNames(fields)
    if (!parsedFields.ok) {
      setError(parsedFields.message)
      return
    }

    setPending(true)
    setError(null)
    try {
      const created = await createWorkflowStep(workflowId, {
        name: parsedName.name,
        stepType: 'VALIDATE_FIELDS',
        position: parsedPosition.position,
        configuration: { requiredFields: parsedFields.names },
      })
      onCreated(created)
      setName('')
      setPosition('')
      setFields('')
    } catch (caught) {
      setError(addStepError(caught))
    } finally {
      setPending(false)
    }
  }

  return (
    <form
      onSubmit={(event) => {
        void onSubmit(event)
      }}
      className="rounded-2xl border border-line bg-navy-900 p-5"
    >
      <h3 className="text-base font-semibold">Add field check</h3>
      <div className="mt-4 grid gap-3">
        <label className="block text-sm" htmlFor="field-step-name">
          Name
          <input
            id="field-step-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className={`${fieldClass} mt-2`}
          />
        </label>
        <label className="block text-sm" htmlFor="field-step-position">
          Position
          <input
            id="field-step-position"
            inputMode="numeric"
            value={position}
            onChange={(event) => setPosition(event.target.value)}
            className={`${fieldClass} mt-2`}
          />
        </label>
        <label className="block text-sm" htmlFor="field-step-fields">
          Required field names
          <textarea
            id="field-step-fields"
            value={fields}
            onChange={(event) => setFields(event.target.value)}
            className={`${fieldClass} mt-2`}
            rows={3}
            placeholder="email, name"
          />
        </label>
      </div>
      {error ? (
        <p className="mt-3 text-sm text-red-200" role="alert">
          {error}
        </p>
      ) : null}
      <button
        type="submit"
        disabled={pending}
        className="mt-4 rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400 disabled:opacity-50"
      >
        {pending ? 'Adding' : 'Add field check'}
      </button>
    </form>
  )
}

function HttpGetForm({
  workflowId,
  onCreated,
}: {
  workflowId: number
  onCreated: (step: WorkflowStep) => void
}) {
  const [name, setName] = useState('')
  const [position, setPosition] = useState('')
  const [url, setUrl] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedName = parseName(name)
    if (!parsedName.ok) {
      setError(parsedName.message)
      return
    }
    const parsedPosition = parsePosition(position)
    if (!parsedPosition.ok) {
      setError(parsedPosition.message)
      return
    }
    const configuration = buildHttpRequestConfiguration('GET', url)
    if (!configuration.ok) {
      setError(configuration.message)
      return
    }

    setPending(true)
    setError(null)
    try {
      const created = await createWorkflowStep(workflowId, {
        name: parsedName.name,
        stepType: 'HTTP_REQUEST',
        position: parsedPosition.position,
        configuration: configuration.configuration,
      })
      onCreated(created)
      setName('')
      setPosition('')
      setUrl('')
    } catch (caught) {
      setError(addStepError(caught))
    } finally {
      setPending(false)
    }
  }

  return (
    <form
      onSubmit={(event) => {
        void onSubmit(event)
      }}
      className="rounded-2xl border border-line bg-navy-900 p-5"
    >
      <h3 className="text-base font-semibold">Add HTTPS GET</h3>
      <div className="mt-4 grid gap-3">
        <label className="block text-sm" htmlFor="http-step-name">
          Name
          <input
            id="http-step-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className={`${fieldClass} mt-2`}
          />
        </label>
        <label className="block text-sm" htmlFor="http-step-position">
          Position
          <input
            id="http-step-position"
            inputMode="numeric"
            value={position}
            onChange={(event) => setPosition(event.target.value)}
            className={`${fieldClass} mt-2`}
          />
        </label>
        <div className="text-sm">
          <p>Method</p>
          <p className="mt-2 rounded-lg border border-line bg-navy-950 px-3 py-2 text-ink">
            GET
          </p>
        </div>
        <label className="block text-sm" htmlFor="http-step-url">
          HTTPS URL
          <input
            id="http-step-url"
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            className={`${fieldClass} mt-2`}
          />
        </label>
      </div>
      {error ? (
        <p className="mt-3 text-sm text-red-200" role="alert">
          {error}
        </p>
      ) : null}
      <button
        type="submit"
        disabled={pending}
        className="mt-4 rounded-lg bg-teal-500 px-4 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400 disabled:opacity-50"
      >
        {pending ? 'Adding' : 'Add HTTPS GET'}
      </button>
    </form>
  )
}
