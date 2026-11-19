import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../api/errors.ts'
import { createWorkflowStep } from '../api/client.ts'
import {
  branchMemberIds,
  buildConditionConfiguration,
  emptyConditionDraft,
  placeCondition,
  savedOrder,
  type ConditionDraft,
} from '../lib/condition.ts'
import { parseName } from '../lib/workflowForm.ts'
import type { WorkflowStep } from '../types/workflow.ts'
import { ConditionFields } from './ConditionFields.tsx'
import { isPositionConflict, POSITION_TAKEN, usableReason } from './editorFeedback.ts'
import { useReportDirty } from './unsavedNavigation.ts'

const quietButton =
  'rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40'

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

export function CreateConditionForm({
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
  const [name, setName] = useState('')
  const [draft, setDraft] = useState<ConditionDraft>(emptyConditionDraft())
  const [trueIds, setTrueIds] = useState<number[]>([])
  const [falseIds, setFalseIds] = useState<number[]>([])
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const dirty =
    name !== '' ||
    draft.field !== '' ||
    draft.operator !== 'EQUALS' ||
    draft.valueKind !== 'string' ||
    draft.valueText !== '' ||
    draft.boolValue !== true ||
    trueIds.length > 0 ||
    falseIds.length > 0
  useReportDirty('add-condition', dirty)

  const claimed = branchMemberIds(steps)
  const selected = new Set([...trueIds, ...falseIds])
  const available = savedOrder(steps).filter(
    (step) =>
      step.stepType !== 'CONDITION' && !selected.has(step.id) && !claimed.has(step.id),
  )

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedName = parseName(name)
    if (!parsedName.ok) {
      setError(parsedName.message)
      return
    }
    const built = buildConditionConfiguration(draft, trueIds, falseIds)
    if (!built.ok) {
      setError(built.message)
      return
    }
    const placed = placeCondition(steps, trueIds, falseIds)
    if (!placed.ok) {
      setError(placed.message)
      return
    }
    const ordered = buildConditionConfiguration(draft, placed.trueSteps, placed.falseSteps)
    if (!ordered.ok) {
      setError(ordered.message)
      return
    }

    setPending(true)
    setError(null)
    try {
      const created = await createWorkflowStep(workflowId, {
        name: parsedName.name,
        stepType: 'CONDITION',
        position: placed.position,
        configuration: ordered.configuration,
        revision,
      })
      onCreated(created)
      setName('')
      setDraft(emptyConditionDraft())
      setTrueIds([])
      setFalseIds([])
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
      className="rounded-2xl border border-amber-400/40 bg-navy-900 p-5 lg:col-span-2"
    >
      <h3 className="text-base font-semibold">Add condition</h3>
      <p className="mt-2 text-sm leading-6 text-muted">
        Create the branch steps first. The condition is saved immediately before
        them, and every later step moves down by one position. Its true and false
        ids are the steps you choose, in position order. A new step cannot be
        inserted between those steps in one save.
      </p>
      <div className="mt-4 grid gap-3">
        <label className="block text-sm" htmlFor="condition-step-name">
          Name
          <input
            id="condition-step-name"
            value={name}
            onChange={(event) => {
              setName(event.target.value)
              setError(null)
            }}
            className={`${fieldClass} mt-2`}
          />
        </label>
        <ConditionFields
          idPrefix="new-condition"
          draft={draft}
          onChange={(next) => {
            setDraft(next)
            setError(null)
          }}
        />
        <div>
          <p className="text-xs font-semibold tracking-wide text-ink">TRUE</p>
          <SelectedSteps
            steps={steps}
            ids={trueIds}
            branch="TRUE"
            onRemove={(id) => setTrueIds(trueIds.filter((item) => item !== id))}
          />
        </div>
        <div>
          <p className="text-xs font-semibold tracking-wide text-ink">FALSE</p>
          <SelectedSteps
            steps={steps}
            ids={falseIds}
            branch="FALSE"
            onRemove={(id) => setFalseIds(falseIds.filter((item) => item !== id))}
          />
        </div>
        {available.length === 0 ? (
          <p className="text-sm text-muted">
            No saved steps are available. Add a field check or an HTTPS GET first.
          </p>
        ) : (
          <ul className="grid gap-2">
            {available.map((step) => (
              <li key={step.id} className="flex flex-wrap items-center gap-2">
                <span className="text-sm text-muted">
                  {step.name} (position {step.position})
                </span>
                <button
                  type="button"
                  className={quietButton}
                  disabled={pending}
                  onClick={() => {
                    setTrueIds([...trueIds, step.id])
                    setError(null)
                  }}
                >
                  {`Add to TRUE: ${step.name} (position ${step.position})`}
                </button>
                <button
                  type="button"
                  className={quietButton}
                  disabled={pending}
                  onClick={() => {
                    setFalseIds([...falseIds, step.id])
                    setError(null)
                  }}
                >
                  {`Add to FALSE: ${step.name} (position ${step.position})`}
                </button>
              </li>
            ))}
          </ul>
        )}
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
        {pending ? 'Adding' : 'Add condition'}
      </button>
    </form>
  )
}

function SelectedSteps({
  steps,
  ids,
  branch,
  onRemove,
}: {
  steps: WorkflowStep[]
  ids: number[]
  branch: 'TRUE' | 'FALSE'
  onRemove: (id: number) => void
}) {
  if (ids.length === 0) {
    return <p className="mt-1 text-sm text-muted">None</p>
  }
  return (
    <ul className="mt-1 grid gap-2">
      {ids.map((id) => {
        const step = steps.find((item) => item.id === id)
        if (!step) {
          return (
            <li key={id} className="text-sm text-muted">
              Missing step {id}
            </li>
          )
        }
        return (
          <li key={id} className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-muted">{step.name}</span>
            <button
              type="button"
              className={quietButton}
              onClick={() => onRemove(id)}
            >
              {`Remove from ${branch}: ${step.name} (position ${step.position})`}
            </button>
          </li>
        )
      })}
    </ul>
  )
}
