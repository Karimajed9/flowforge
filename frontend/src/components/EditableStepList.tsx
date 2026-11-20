import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/errors.ts'
import { editorMutationMessage } from './editorFeedback.ts'
import { useReportDirty } from './unsavedNavigation.ts'
import {
  deleteWorkflowStep,
  reorderWorkflowSteps,
  updateWorkflowStep,
} from '../api/client.ts'
import {
  CONDITION_MESSAGES,
  branchesFollowCondition,
  buildConditionConfiguration,
  conditionDraftDiffers,
  deletionBreaksBranch,
  emptyConditionDraft,
  moveBreaksBranch,
  readBranchIds,
  readCondition,
  savedOrder,
  branchRole,
  type ConditionDraft,
} from '../lib/condition.ts'
import { configurationText, stepDraftDiffers } from '../lib/stepDraft.ts'
import {
  buildHttpRequestConfiguration,
  httpDraftDiffers,
  httpDraftFrom,
  parseName,
  parseRequiredFieldNames,
  type HttpDraft,
} from '../lib/workflowForm.ts'
import type { WorkflowStep } from '../types/workflow.ts'
import { ConditionBranchControls, ConditionFields } from './ConditionFields.tsx'
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
  const [httpDraft, setHttpDraft] = useState<HttpDraft>({
    method: 'GET',
    url: '',
    bodySource: 'STATIC_JSON',
    bodyText: '',
  })
  const [conditionDraft, setConditionDraft] = useState<ConditionDraft>(emptyConditionDraft())
  const [trueSteps, setTrueSteps] = useState<number[]>([])
  const [falseSteps, setFalseSteps] = useState<number[]>([])
  const [confirmId, setConfirmId] = useState<number | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const editingStep = steps.find((step) => step.id === editingId) ?? null
  const dirty =
    editingStep !== null &&
    (editingStep.stepType === 'CONDITION'
      ? conditionDraftDiffers(
          editingStep,
          draftName,
          conditionDraft,
          trueSteps,
          falseSteps,
        )
      : editingStep.stepType === 'HTTP_REQUEST'
        ? draftName.trim() !== editingStep.name.trim() ||
          httpDraftDiffers(editingStep.configuration, httpDraft)
        : stepDraftDiffers(editingStep, {
            name: draftName,
            stepType: editingStep.stepType,
            configurationText: draftConfig,
          }))

  useReportDirty('step-editor', dirty)

  function guard(): boolean {
    if (!dirty) {
      return true
    }
    return window.confirm(UNSAVED)
  }

  function closeEditor() {
    setEditingId(null)
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
    if (step.stepType === 'HTTP_REQUEST') {
      setHttpDraft(httpDraftFrom(step.configuration))
    }
    if (step.stepType === 'CONDITION') {
      const branches = readBranchIds(step.configuration)
      setConditionDraft(readCondition(step.configuration))
      setTrueSteps(branches?.trueSteps ?? [])
      setFalseSteps(branches?.falseSteps ?? [])
    }
    setError(null)
  }

  function configurationForSave(
    step: WorkflowStep,
  ):
    | { ok: true; configuration: Record<string, unknown> }
    | { ok: false; message: string } {
    if (step.stepType === 'CONDITION') {
      const built = buildConditionConfiguration(conditionDraft, trueSteps, falseSteps)
      if (!built.ok) {
        return built
      }
      const preview = savedOrder(
        steps.map((item) =>
          item.id === step.id ? { ...item, configuration: built.configuration } : item,
        ),
      )
      if (!branchesFollowCondition(preview)) {
        return { ok: false, message: CONDITION_MESSAGES.span }
      }
      return built
    }
    if (step.stepType === 'HTTP_REQUEST') {
      const parsed = buildHttpRequestConfiguration(httpDraft.method, httpDraft.url, {
        source: httpDraft.bodySource,
        text: httpDraft.bodyText,
      })
      if (!parsed.ok) {
        return parsed
      }
      return { ok: true, configuration: parsed.configuration }
    }
    return configurationFor(step, draftConfig)
  }

  async function save(event: FormEvent<HTMLFormElement>, step: WorkflowStep) {
    event.preventDefault()
    const parsedName = parseName(draftName)
    if (!parsedName.ok) {
      setError(parsedName.message)
      return
    }
    const configuration = configurationForSave(step)
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
      setError(
        caught instanceof ApiError
          ? editorMutationMessage(caught, 'The step was not saved')
          : 'The step was not saved',
      )
    } finally {
      setPending(false)
    }
  }

  async function remove(step: WorkflowStep) {
    const blocked = deletionBreaksBranch(steps, step.id)
    if (blocked) {
      setError(blocked)
      return
    }
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
      setError(
        caught instanceof ApiError
          ? editorMutationMessage(caught, 'The step was not deleted')
          : 'The step was not deleted',
      )
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
    const blocked = moveBreaksBranch(steps, index, direction)
    if (blocked) {
      setError(blocked)
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
      setError(
        caught instanceof ApiError
          ? editorMutationMessage(caught, 'The steps were not reordered')
          : 'The steps were not reordered',
      )
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
          description="This workflow has no steps. Add a field check, an HTTPS GET, or a condition below."
        />
      ) : (
        <ol className="grid gap-4">
          {steps.map((step, index) => {
            const editing = editingId === step.id
            const role = branchRole(steps, step.id)
            const deleteBlocked = deletionBreaksBranch(steps, step.id)
            return (
              <li
                key={step.id}
                className={`rounded-2xl border bg-navy-900 p-5 ${
                  step.stepType === 'CONDITION' ? 'border-amber-400/40' : 'border-line'
                }`}
              >
                <p className="text-xs font-semibold tracking-wide text-muted">
                  Position {step.position}
                </p>
                <h3 className="mt-1 text-lg font-semibold">{step.name}</h3>
                <p
                  className={`mt-1 text-sm ${
                    step.stepType === 'CONDITION'
                      ? 'font-semibold text-amber-200'
                      : 'text-muted'
                  }`}
                >
                  {step.stepType}
                </p>
                {role === 'true' ? (
                  <p className="mt-1 text-xs font-semibold tracking-wide text-teal-300">
                    TRUE branch
                  </p>
                ) : null}
                {role === 'false' ? (
                  <p className="mt-1 text-xs font-semibold tracking-wide text-rose-200">
                    FALSE branch
                  </p>
                ) : null}
                <StepConfigurationSummary step={step} steps={steps} />
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
                    {deleteBlocked ? (
                      <>
                        <p className="text-sm leading-6 text-red-200" role="alert">
                          {deleteBlocked}
                        </p>
                        <button
                          type="button"
                          className={`${quietButton} mt-3`}
                          disabled={pending}
                          onClick={() => setConfirmId(null)}
                        >
                          Keep step
                        </button>
                      </>
                    ) : (
                      <>
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
                      </>
                    )}
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
                          setError(null)
                        }}
                        className={`${fieldClass} mt-2`}
                      />
                    </label>
                    {step.stepType === 'CONDITION' ? (
                      <>
                        <ConditionFields
                          idPrefix={`step-condition-${step.id}`}
                          draft={conditionDraft}
                          onChange={(next) => {
                            setConditionDraft(next)
                            setError(null)
                          }}
                        />
                        <ConditionBranchControls
                          steps={steps}
                          conditionId={step.id}
                          trueSteps={trueSteps}
                          falseSteps={falseSteps}
                          onChange={(nextTrue, nextFalse) => {
                            setTrueSteps(nextTrue)
                            setFalseSteps(nextFalse)
                            setError(null)
                          }}
                        />
                      </>
                    ) : null}
                    {step.stepType === 'VALIDATE_FIELDS' ? (
                      <label className="block text-sm" htmlFor={`step-fields-${step.id}`}>
                        Required field names
                        <textarea
                          id={`step-fields-${step.id}`}
                          value={draftConfig}
                          rows={3}
                          onChange={(event) => {
                            setDraftConfig(event.target.value)
                            setError(null)
                          }}
                          className={`${fieldClass} mt-2`}
                        />
                      </label>
                    ) : null}
                    {step.stepType === 'HTTP_REQUEST' ? (
                      <>
                        <label className="block text-sm" htmlFor={`step-method-${step.id}`}>
                          Method
                          <select
                            id={`step-method-${step.id}`}
                            value={httpDraft.method}
                            onChange={(event) => {
                              setHttpDraft({
                                ...httpDraft,
                                method: event.target.value === 'POST' ? 'POST' : 'GET',
                              })
                              setError(null)
                            }}
                            className={`${fieldClass} mt-2`}
                          >
                            <option value="GET">GET</option>
                            <option value="POST">POST</option>
                          </select>
                        </label>
                        <label className="block text-sm" htmlFor={`step-url-${step.id}`}>
                          HTTPS URL
                          <input
                            id={`step-url-${step.id}`}
                            value={httpDraft.url}
                            onChange={(event) => {
                              setHttpDraft({ ...httpDraft, url: event.target.value })
                              setError(null)
                            }}
                            className={`${fieldClass} mt-2`}
                          />
                        </label>
                        {httpDraft.method === 'POST' ? (
                          <>
                            <label className="block text-sm" htmlFor={`step-body-source-${step.id}`}>
                              Body source
                              <select
                                id={`step-body-source-${step.id}`}
                                value={httpDraft.bodySource}
                                onChange={(event) => {
                                  setHttpDraft({
                                    ...httpDraft,
                                    bodySource:
                                      event.target.value === 'WORKFLOW_PAYLOAD'
                                        ? 'WORKFLOW_PAYLOAD'
                                        : 'STATIC_JSON',
                                  })
                                  setError(null)
                                }}
                                className={`${fieldClass} mt-2`}
                              >
                                <option value="STATIC_JSON">Static JSON</option>
                                <option value="WORKFLOW_PAYLOAD">Workflow payload</option>
                              </select>
                            </label>
                            {httpDraft.bodySource === 'STATIC_JSON' ? (
                              <label className="block text-sm" htmlFor={`step-body-${step.id}`}>
                                JSON body
                                <textarea
                                  id={`step-body-${step.id}`}
                                  value={httpDraft.bodyText}
                                  rows={5}
                                  onChange={(event) => {
                                    setHttpDraft({ ...httpDraft, bodyText: event.target.value })
                                    setError(null)
                                  }}
                                  className={`${fieldClass} mt-2 font-mono`}
                                />
                              </label>
                            ) : null}
                          </>
                        ) : null}
                      </>
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

function replaceStep(steps: WorkflowStep[], saved: WorkflowStep): WorkflowStep[] {
  return steps
    .map((step) => (step.id === saved.id ? saved : step))
    .sort((left, right) => left.position - right.position || left.id - right.id)
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
  if (step.stepType === 'HTTP_REQUEST') {
    const parsed = buildHttpRequestConfiguration('GET', draft)
    if (!parsed.ok) {
      return parsed
    }
    return { ok: true, configuration: parsed.configuration }
  }
  return { ok: false, message: 'This step type cannot be saved from this form' }
}
