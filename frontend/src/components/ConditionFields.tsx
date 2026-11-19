import {
  CONDITION_OPERATORS,
  branchAppend,
  type ComparisonKind,
  type ConditionDraft,
} from '../lib/condition.ts'
import type { WorkflowStep } from '../types/workflow.ts'

const fieldClass =
  'w-full rounded-lg border border-line bg-navy-800 px-3 py-2 text-sm text-ink'

const quietButton =
  'rounded-lg border border-line px-3 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40'

export function ConditionFields({
  idPrefix,
  draft,
  onChange,
}: {
  idPrefix: string
  draft: ConditionDraft
  onChange: (draft: ConditionDraft) => void
}) {
  const numericOnly =
    draft.operator === 'GREATER_THAN' || draft.operator === 'LESS_THAN'
  const showValue = draft.operator !== 'EXISTS'

  return (
    <>
      <label className="block text-sm" htmlFor={`${idPrefix}-field`}>
        Field name
        <input
          id={`${idPrefix}-field`}
          value={draft.field}
          onChange={(event) => onChange({ ...draft, field: event.target.value })}
          className={`${fieldClass} mt-2`}
        />
      </label>
      <label className="block text-sm" htmlFor={`${idPrefix}-operator`}>
        Operator
        <select
          id={`${idPrefix}-operator`}
          value={draft.operator}
          onChange={(event) => onChange(withOperator(draft, event.target.value))}
          className={`${fieldClass} mt-2`}
        >
          {CONDITION_OPERATORS.map((operator) => (
            <option key={operator} value={operator}>
              {operator}
            </option>
          ))}
        </select>
      </label>
      {showValue && !numericOnly ? (
        <label className="block text-sm" htmlFor={`${idPrefix}-value-kind`}>
          Value type
          <select
            id={`${idPrefix}-value-kind`}
            value={draft.valueKind}
            onChange={(event) =>
              onChange({ ...draft, valueKind: comparisonKind(event.target.value) })
            }
            className={`${fieldClass} mt-2`}
          >
            <option value="string">String</option>
            <option value="number">Number</option>
            <option value="boolean">Boolean</option>
            <option value="null">Null</option>
          </select>
        </label>
      ) : null}
      {showValue && (numericOnly || draft.valueKind === 'string' || draft.valueKind === 'number') ? (
        <label className="block text-sm" htmlFor={`${idPrefix}-value`}>
          Comparison value
          <input
            id={`${idPrefix}-value`}
            value={draft.valueText}
            inputMode={numericOnly || draft.valueKind === 'number' ? 'decimal' : 'text'}
            onChange={(event) => onChange({ ...draft, valueText: event.target.value })}
            className={`${fieldClass} mt-2`}
          />
        </label>
      ) : null}
      {showValue && !numericOnly && draft.valueKind === 'boolean' ? (
        <label className="block text-sm" htmlFor={`${idPrefix}-bool`}>
          Boolean value
          <select
            id={`${idPrefix}-bool`}
            value={draft.boolValue ? 'true' : 'false'}
            onChange={(event) =>
              onChange({ ...draft, boolValue: event.target.value === 'true' })
            }
            className={`${fieldClass} mt-2`}
          >
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        </label>
      ) : null}
      {showValue && !numericOnly && draft.valueKind === 'null' ? (
        <p className="text-sm text-muted">The comparison value is null.</p>
      ) : null}
      {draft.operator === 'EXISTS' ? (
        <p className="text-sm text-muted">EXISTS checks that the field is present. It has no comparison value.</p>
      ) : null}
    </>
  )
}

export function ConditionBranchControls({
  steps,
  conditionId,
  trueSteps,
  falseSteps,
  onChange,
}: {
  steps: WorkflowStep[]
  conditionId: number
  trueSteps: number[]
  falseSteps: number[]
  onChange: (trueSteps: number[], falseSteps: number[]) => void
}) {
  const candidate = branchAppend(steps, conditionId, trueSteps, falseSteps)
  const removable = removableId(trueSteps, falseSteps)
  const removableStep = removable === null ? undefined : findStep(steps, removable)

  return (
    <div className="grid gap-3">
      <BranchNames label="TRUE" steps={steps} ids={trueSteps} />
      <BranchNames label="FALSE" steps={steps} ids={falseSteps} />
      <p className="text-sm leading-6 text-muted">
        A step can be added to the end of the false branch. It can be added to the true
        branch only when the false branch is empty. Inserting a step between the branches,
        or deleting a step this condition still names, cannot be saved with the current API.
      </p>
      {candidate ? (
        <div className="flex flex-wrap gap-2">
          {candidate.allowTrue ? (
            <button
              type="button"
              className={quietButton}
              onClick={() => onChange([...trueSteps, candidate.step.id], falseSteps)}
            >
              {addLabel('true', candidate.step)}
            </button>
          ) : null}
          <button
            type="button"
            className={quietButton}
            onClick={() => onChange(trueSteps, [...falseSteps, candidate.step.id])}
          >
            {addLabel('false', candidate.step)}
          </button>
        </div>
      ) : null}
      {removableStep ? (
        <button
          type="button"
          className={quietButton}
          onClick={() => onChange(...withoutLast(trueSteps, falseSteps))}
        >
          {`Remove ${stepLabel(removableStep)} from the ${falseSteps.length > 0 ? 'false' : 'true'} branch`}
        </button>
      ) : null}
    </div>
  )
}

function BranchNames({
  label,
  steps,
  ids,
}: {
  label: string
  steps: WorkflowStep[]
  ids: number[]
}) {
  return (
    <div>
      <p className="text-xs font-semibold tracking-wide text-ink">{label}</p>
      {ids.length === 0 ? (
        <p className="mt-1 text-sm text-muted">None</p>
      ) : (
        <ul className="mt-1 grid gap-1">
          {ids.map((id) => {
            const step = findStep(steps, id)
            return (
              <li key={id} className="text-sm text-muted">
                {step ? step.name : `Missing step ${id}`}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function withOperator(draft: ConditionDraft, value: string): ConditionDraft {
  const operator = CONDITION_OPERATORS.find((item) => item === value) ?? draft.operator
  return {
    ...draft,
    operator,
    valueKind: operator === 'GREATER_THAN' || operator === 'LESS_THAN' ? 'number' : draft.valueKind,
  }
}

function removableId(trueSteps: number[], falseSteps: number[]): number | null {
  if (trueSteps.length + falseSteps.length <= 1) {
    return null
  }
  if (falseSteps.length > 0) {
    return falseSteps[falseSteps.length - 1] ?? null
  }
  return trueSteps[trueSteps.length - 1] ?? null
}

function withoutLast(trueSteps: number[], falseSteps: number[]): [number[], number[]] {
  if (falseSteps.length > 0) {
    return [trueSteps, falseSteps.slice(0, -1)]
  }
  return [trueSteps.slice(0, -1), falseSteps]
}

function findStep(steps: WorkflowStep[], id: number): WorkflowStep | undefined {
  return steps.find((step) => step.id === id)
}

function addLabel(branch: 'true' | 'false', step: WorkflowStep): string {
  return `Add ${stepLabel(step)} to the ${branch} branch`
}

function stepLabel(step: WorkflowStep): string {
  return `${step.name} (position ${step.position})`
}

function comparisonKind(value: string): ComparisonKind {
  if (value === 'number' || value === 'boolean' || value === 'null') {
    return value
  }
  return 'string'
}
