import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/errors.ts'
import { createWorkflowStep } from '../api/client.ts'
import { insertionBreaksBranch } from '../lib/condition.ts'
import { isPositionConflict, POSITION_TAKEN, usableReason } from './editorFeedback.ts'
import { useReportDirty } from './unsavedNavigation.ts'
import {
  buildHttpRequestConfiguration,
  parseName,
  parsePosition,
  parseRequiredFieldNames,
} from '../lib/workflowForm.ts'
import type { WorkflowStep } from '../types/workflow.ts'
import { CreateConditionForm } from './CreateConditionForm.tsx'

const fieldClass =
  'w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink'

function addStepError(caught: unknown): string {
  if (!(caught instanceof ApiError)) {
    return 'The step was not added'
  }
  if (isPositionConflict(caught)) {
    return POSITION_TAKEN
  }
  const reason = usableReason(caught.message)
  if (caught.status === 409 && !reason) {
    return 'The step was not added'
  }
  return reason ?? 'The step was not added'
}

export function CreateStepForms({
  workflowId,
  steps,
  revision,
  onCreated,
}: {
  workflowId: number
  steps: WorkflowStep[]
  revision: number
  onCreated: (step: WorkflowStep) => void
}) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <ValidateFieldsForm workflowId={workflowId} steps={steps} onCreated={onCreated} />
      <HttpRequestForm workflowId={workflowId} steps={steps} onCreated={onCreated} />
      <CreateConditionForm
        workflowId={workflowId}
        steps={steps}
        revision={revision}
        onCreated={onCreated}
      />
    </div>
  )
}

function ValidateFieldsForm({
  workflowId,
  steps,
  onCreated,
}: {
  workflowId: number
  steps: WorkflowStep[]
  onCreated: (step: WorkflowStep) => void
}) {
  const [name, setName] = useState('')
  const [position, setPosition] = useState('')
  const [fields, setFields] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  useReportDirty('add-field-check', name !== '' || position !== '' || fields !== '')

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
    const blocked = insertionBreaksBranch(steps, parsedPosition.position)
    if (blocked) {
      setError(blocked)
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

function HttpRequestForm({
  workflowId,
  steps,
  onCreated,
}: {
  workflowId: number
  steps: WorkflowStep[]
  onCreated: (step: WorkflowStep) => void
}) {
  const [name, setName] = useState('')
  const [position, setPosition] = useState('')
  const [method, setMethod] = useState<'GET' | 'POST'>('GET')
  const [url, setUrl] = useState('')
  const [bodySource, setBodySource] = useState<'STATIC_JSON' | 'WORKFLOW_PAYLOAD'>('STATIC_JSON')
  const [bodyText, setBodyText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  useReportDirty(
    'add-https-get',
    name !== '' || position !== '' || url !== '' || method !== 'GET' || bodyText !== '',
  )

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
    const configuration = buildHttpRequestConfiguration(method, url, {
      source: bodySource,
      text: bodyText,
    })
    if (!configuration.ok) {
      setError(configuration.message)
      return
    }
    const blocked = insertionBreaksBranch(steps, parsedPosition.position)
    if (blocked) {
      setError(blocked)
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
      setMethod('GET')
      setBodySource('STATIC_JSON')
      setBodyText('')
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
      <h3 className="text-base font-semibold">
        {method === 'POST' ? 'Add HTTPS POST' : 'Add HTTPS GET'}
      </h3>
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
        <label className="block text-sm" htmlFor="http-step-method">
          Method
          <select
            id="http-step-method"
            value={method}
            onChange={(event) => {
              setMethod(event.target.value === 'POST' ? 'POST' : 'GET')
              setError(null)
            }}
            className={`${fieldClass} mt-2`}
          >
            <option value="GET">GET</option>
            <option value="POST">POST</option>
          </select>
        </label>
        <label className="block text-sm" htmlFor="http-step-url">
          HTTPS URL
          <input
            id="http-step-url"
            value={url}
            onChange={(event) => {
              setUrl(event.target.value)
              setError(null)
            }}
            className={`${fieldClass} mt-2`}
          />
        </label>
        {method === 'POST' ? (
          <>
            <label className="block text-sm" htmlFor="http-step-body-source">
              Body source
              <select
                id="http-step-body-source"
                value={bodySource}
                onChange={(event) => {
                  setBodySource(
                    event.target.value === 'WORKFLOW_PAYLOAD' ? 'WORKFLOW_PAYLOAD' : 'STATIC_JSON',
                  )
                  setError(null)
                }}
                className={`${fieldClass} mt-2`}
              >
                <option value="STATIC_JSON">Static JSON</option>
                <option value="WORKFLOW_PAYLOAD">Workflow payload</option>
              </select>
            </label>
            {bodySource === 'STATIC_JSON' ? (
              <label className="block text-sm" htmlFor="http-step-body">
                JSON body
                <textarea
                  id="http-step-body"
                  value={bodyText}
                  rows={5}
                  onChange={(event) => {
                    setBodyText(event.target.value)
                    setError(null)
                  }}
                  className={`${fieldClass} mt-2 font-mono`}
                />
              </label>
            ) : null}
          </>
        ) : null}
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
        {pending ? 'Adding' : method === 'POST' ? 'Add HTTPS POST' : 'Add HTTPS GET'}
      </button>
    </form>
  )
}
