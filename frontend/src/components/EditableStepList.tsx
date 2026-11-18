import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/errors.ts'
import {
  deleteWorkflowStep,
  reorderWorkflowSteps,
  updateWorkflowStep,
} from '../api/client.ts'
import {
  buildHttpRequestConfiguration,
  parseName,
  parseRequiredFieldNames,
} from '../lib/workflowForm.ts'
import type { WorkflowStep } from '../types/workflow.ts'
import { StepConfigurationSummary } from './StepConfigurationSummary.tsx'
import { EmptyState } from './EmptyState.tsx'

const fieldClass =
  'w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink'

const quietButton =
  'rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40'

const UNSAVED = 'Unsaved step changes will be lost.'

export function EditableStepList({
  workflowId,
  steps,
  revision,
  onChange,
}: {
  workflowId: number
  steps: WorkflowStep[]
  revision: number
  onChange: (steps: WorkflowStep[], revision: number) => void
}) {
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draftName, setDraftName] = useState('')
  const [draftConfig, setDraftConfig] = useState('')
  const [dirty, setDirty] = useState(false)
  const [confirmId, setConfirmId] = useState<number | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useUnsavedWarning(dirty)

  function guard(): boolean {
    if (!dirty) {
      return true
    }
    return window.confirm(UNSAVED)
  }

  function closeEditor() {
    setEditingId(null)
    setDirty(false)
    setError(null)
  }

  function openEditor(step: WorkflowStep) {
    if (editingId === step.id) {
      return
    }
    if (!guard()) {
      return
    }
    setConfirmId(null)
    setEditingId(step.id)
    setDraftName(step.name)
    setDraftConfig(configurationText(step))
    setDirty(false)
    setError(null)
  }

  async function save(event: FormEvent<HTMLFormElement>, step: WorkflowStep) {
    event.preventDefault()
    const parsedName = parseName(draftName)
    if (!parsedName.ok) {
      setError(parsedName.message)
      return
    }
    const configuration = configurationFor(step, draftConfig)
    if (!configuration.ok) {
      setError(configuration.message)
      return
    }
    setPending(true)
    setError(null)
    try {
      const saved = await updateWorkflowStep(workflowId, step.id, {
        name: parsedName.name,
        configuration: configuration.configuration,
        revision,
      })
      onChange(replaceStep(steps, saved.step), saved.stepRevision)
      closeEditor()
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'The step was not saved')
    } finally {
      setPending(false)
    }
  }

  async function remove(step: WorkflowStep) {
    setPending(true)
    setError(null)
    try {
      const saved = await deleteWorkflowStep(workflowId, step.id, revision)
      onChange(saved.steps, saved.stepRevision)
      setConfirmId(null)
      if (editingId === step.id) {
        closeEditor()
      }
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'The step was not deleted')
    } finally {
      setPending(false)
    }
  }

  async function move(index: number, direction: -1 | 1) {
    if (!guard()) {
      return
    }
    const nextIndex = index + direction
    if (nextIndex < 0 || nextIndex >= steps.length) {
      return
    }
    const order = steps.map((step) => step.id)
    const current = order[index]
    order[index] = order[nextIndex] as number
    order[nextIndex] = current as number
    setPending(true)
    setError(null)
    try {
      const saved = await reorderWorkflowSteps(workflowId, {
        stepIds: order,
        revision,
      })
      onChange(saved.steps, saved.stepRevision)
      closeEditor()
      setConfirmId(null)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'The steps were not reordered')
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="flex flex-col gap-4" aria-busy={pending || undefined}>
      <h2 className="text-lg font-semibold">Steps</h2>
      {error && editingId === null ? (
        <p className="text-sm text-red-200" role="alert">
          {error}
        </p>
      ) : null}
      {steps.length === 0 ? (
        <EmptyState
          title="No steps yet"
          description="This workflow has no steps. Add a field check or an HTTPS GET below."
        />
      ) : (
        <ol className="grid gap-4">
          {steps.map((step, index) => {
            const editing = editingId === step.id
            return (
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
                <div className="mt-4 flex flex-wrap gap-2">
                  <button
                    type="button"
                    className={quietButton}
                    disabled={pending || index === 0}
                    onClick={() => void move(index, -1)}
                  >
                    Move {step.name} up
                  </button>
                  <button
                    type="button"
                    className={quietButton}
                    disabled={pending || index === steps.length - 1}
                    onClick={() => void move(index, 1)}
                  >
                    Move {step.name} down
                  </button>
                  <button
                    type="button"
                    className={quietButton}
                    disabled={pending}
                    onClick={() => openEditor(step)}
                  >
                    Edit {step.name}
                  </button>
                  <button
                    type="button"
                    className={quietButton}
                    disabled={pending}
                    onClick={() => {
                      if (!guard()) {
                        return
                      }
                      setError(null)
                      setConfirmId(step.id)
                    }}
                  >
                    Delete {step.name}
                  </button>
                </div>
                {confirmId === step.id ? (
                  <div className="mt-4 rounded-xl border border-line bg-navy-950 p-4">
                    <p className="text-sm leading-6 text-ink">
                      Delete this step? Runs that already started keep the steps
                      they began with.
                    </p>
                    <div className="mt-3 flex flex-wrap gap-2">
                      <button
                        type="button"
                        className="rounded-lg bg-red-400 px-3 py-2 text-sm font-semibold text-navy-950 hover:bg-red-300 disabled:opacity-40"
                        disabled={pending}
                        onClick={() => void remove(step)}
                      >
                        {pending ? 'Deleting' : 'Confirm delete'}
                      </button>
                      <button
                        type="button"
                        className={quietButton}
                        disabled={pending}
                        onClick={() => setConfirmId(null)}
                      >
                        Keep step
                      </button>
                    </div>
                  </div>
                ) : null}
                {editing ? (
                  <form
                    className="mt-4 grid gap-3"
                    onSubmit={(event) => void save(event, step)}
                  >
                    <label className="block text-sm" htmlFor={`step-name-${step.id}`}>
                      Name
                      <input
                        id={`step-name-${step.id}`}
                        value={draftName}
                        onChange={(event) => {
                          setDraftName(event.target.value)
                          setDirty(true)
                          setError(null)
                        }}
                        className={`${fieldClass} mt-2`}
                      />
                    </label>
                    {step.stepType === 'VALIDATE_FIELDS' ? (
                      <label className="block text-sm" htmlFor={`step-fields-${step.id}`}>
                        Required field names
                        <textarea
                          id={`step-fields-${step.id}`}
                          value={draftConfig}
                          rows={3}
                          onChange={(event) => {
                            setDraftConfig(event.target.value)
                            setDirty(true)
                            setError(null)
                          }}
                          className={`${fieldClass} mt-2`}
                        />
                      </label>
                    ) : (
                      <label className="block text-sm" htmlFor={`step-url-${step.id}`}>
                        HTTPS URL
                        <input
                          id={`step-url-${step.id}`}
                          value={draftConfig}
                          onChange={(event) => {
                            setDraftConfig(event.target.value)
                            setDirty(true)
                            setError(null)
                          }}
                          className={`${fieldClass} mt-2`}
                        />
                      </label>
                    )}
                    {step.stepType === 'HTTP_REQUEST' ? (
                      <p className="text-sm text-muted">Method: GET</p>
                    ) : null}
                    {error ? (
                      <p className="text-sm text-red-200" role="alert">
                        {error}
                      </p>
                    ) : null}
                    <div className="flex flex-wrap gap-2">
                      <button
                        type="submit"
                        disabled={pending}
                        className="rounded-lg bg-teal-500 px-3 py-2 text-sm font-semibold text-navy-950 hover:bg-teal-400 disabled:opacity-40"
                      >
                        {pending ? 'Saving' : 'Save'}
                      </button>
                      <button
                        type="button"
                        className={quietButton}
                        disabled={pending}
                        onClick={() => {
                          if (!dirty || window.confirm(UNSAVED)) {
                            closeEditor()
                          }
                        }}
                      >
                        Cancel
                      </button>
                    </div>
                  </form>
                ) : null}
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}

function useUnsavedWarning(active: boolean) {
  useEffect(() => {
    if (!active) {
      return
    }
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    const onClick = (event: MouseEvent) => {
      const target = event.target
      if (!(target instanceof Element)) {
        return
      }
      const link = target.closest('a')
      if (!link || link.target === '_blank') {
        return
      }
      if (!window.confirm(UNSAVED)) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    document.addEventListener('click', onClick, true)
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload)
      document.removeEventListener('click', onClick, true)
    }
  }, [active])
}

function replaceStep(steps: WorkflowStep[], saved: WorkflowStep): WorkflowStep[] {
  return steps
    .map((step) => (step.id === saved.id ? saved : step))
    .sort((left, right) => left.position - right.position || left.id - right.id)
}

function configurationText(step: WorkflowStep): string {
  if (step.stepType === 'VALIDATE_FIELDS') {
    const fields = Array.isArray(step.configuration.requiredFields)
      ? step.configuration.requiredFields.filter(
          (field): field is string => typeof field === 'string',
        )
      : []
    return fields.join(', ')
  }
  return typeof step.configuration.url === 'string' ? step.configuration.url : ''
}

function configurationFor(
  step: WorkflowStep,
  draft: string,
):
  | { ok: true; configuration: Record<string, unknown> }
  | { ok: false; message: string } {
  if (step.stepType === 'VALIDATE_FIELDS') {
    const parsed = parseRequiredFieldNames(draft)
    if (!parsed.ok) {
      return parsed
    }
    return { ok: true, configuration: { requiredFields: parsed.names } }
  }
  const parsed = buildHttpRequestConfiguration('GET', draft)
  if (!parsed.ok) {
    return parsed
  }
  return { ok: true, configuration: parsed.configuration }
}
