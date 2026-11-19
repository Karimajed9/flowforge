import { expect, test } from 'vitest'
import {
  CONDITION_MESSAGES,
  branchAppend,
  branchesFollowCondition,
  buildConditionConfiguration,
  conditionDraftDiffers,
  deletionBreaksBranch,
  emptyConditionDraft,
  insertionBreaksBranch,
  moveBreaksBranch,
  placeCondition,
  readCondition,
  reorderKeepsBranches,
} from './condition.ts'
import type { WorkflowStep } from '../types/workflow.ts'

function step(
  id: number,
  position: number,
  stepType: WorkflowStep['stepType'],
  configuration: Record<string, unknown> = { requiredFields: ['email'] },
  name = `Step ${id}`,
): WorkflowStep {
  return { id, name, stepType, position, configuration }
}

function condition(
  trueSteps: number[],
  falseSteps: number[],
  value: unknown = 10,
  operator = 'EQUALS',
): WorkflowStep {
  return step(10, 1, 'CONDITION', {
    field: 'amount',
    operator,
    value,
    trueSteps,
    falseSteps,
  }, 'Amount is 10')
}

const notify = step(12, 2, 'VALIDATE_FIELDS', { requiredFields: ['email'] }, 'Notify')
const record = step(13, 3, 'HTTP_REQUEST', { method: 'GET', url: 'https://example.com/hook' }, 'Record')

test('a comparison value keeps its JSON type', () => {
  const branches = [12]
  const numberValue = buildConditionConfiguration(
    { ...emptyConditionDraft(), field: 'amount', valueKind: 'number', valueText: '10' },
    branches,
    [],
  )
  const decimal = buildConditionConfiguration(
    { ...emptyConditionDraft(), field: 'amount', valueKind: 'number', valueText: '10.0' },
    branches,
    [],
  )
  const text = buildConditionConfiguration(
    { ...emptyConditionDraft(), field: 'amount', valueKind: 'string', valueText: '10' },
    branches,
    [],
  )
  const flag = buildConditionConfiguration(
    { ...emptyConditionDraft(), field: 'amount', valueKind: 'boolean', boolValue: true },
    branches,
    [],
  )
  const empty = buildConditionConfiguration(
    { ...emptyConditionDraft(), field: 'amount', valueKind: 'null' },
    branches,
    [],
  )
  const present = buildConditionConfiguration(
    { ...emptyConditionDraft(), field: 'amount', operator: 'EXISTS' },
    branches,
    [],
  )
  const greater = buildConditionConfiguration(
    {
      ...emptyConditionDraft(),
      field: 'amount',
      operator: 'GREATER_THAN',
      valueKind: 'number',
      valueText: 'ten',
    },
    branches,
    [],
  )

  expect(numberValue.ok && numberValue.configuration.value).toBe(10)
  expect(decimal.ok && decimal.configuration.value).toBe(10)
  expect(text.ok && text.configuration.value).toBe('10')
  expect(flag.ok && flag.configuration.value).toBe(true)
  expect(empty.ok && empty.configuration.value).toBeNull()
  expect(present.ok && present.configuration).not.toHaveProperty('value')
  expect(greater.ok).toBe(false)
  if (!greater.ok) {
    expect(greater.message).toBe(CONDITION_MESSAGES.valueNumber)
  }
})

test('invalid condition fields and empty branches are rejected', () => {
  expect(
    buildConditionConfiguration(
      { ...emptyConditionDraft(), field: 'amount.total', valueKind: 'number', valueText: '1' },
      [12],
      [],
    ).ok,
  ).toBe(false)
  expect(
    buildConditionConfiguration(
      { ...emptyConditionDraft(), field: 'amount', valueKind: 'number', valueText: '1' },
      [],
      [],
    ),
  ).toMatchObject({ ok: false, message: CONDITION_MESSAGES.branchRequired })
})

test('opening a saved condition is clean and restoring the value clears dirty', () => {
  const saved = condition([12], [13])
  const draft = readCondition(saved.configuration)
  expect(conditionDraftDiffers(saved, saved.name, draft, [12], [13])).toBe(false)
  expect(
    conditionDraftDiffers(saved, saved.name, { ...draft, valueText: '11' }, [12], [13]),
  ).toBe(true)
  expect(
    conditionDraftDiffers(saved, saved.name, { ...draft, valueText: '10' }, [12], [13]),
  ).toBe(false)
  expect(
    conditionDraftDiffers(
      saved,
      saved.name,
      { ...draft, valueKind: 'string', valueText: '10' },
      [12],
      [13],
    ),
  ).toBe(true)
  expect(
    conditionDraftDiffers(saved, saved.name, { ...draft, valueText: '10abc' }, [12], [13]),
  ).toBe(true)
})

test('a new condition is inserted immediately before its branch steps', () => {
  const placed = placeCondition([record, notify], [13, 12], [])
  expect(placed).toEqual({ ok: true, position: 2, trueSteps: [12, 13], falseSteps: [] })

  const both = placeCondition([notify, record], [12], [13])
  expect(both).toEqual({ ok: true, position: 2, trueSteps: [12], falseSteps: [13] })

  const backwards = placeCondition([notify, record], [13], [12])
  expect(backwards).toMatchObject({ ok: false, message: CONDITION_MESSAGES.trueBeforeFalse })

  const gapped = placeCondition(
    [
      notify,
      step(14, 3, 'VALIDATE_FIELDS', { requiredFields: ['ready'] }, 'Middle'),
      { ...record, position: 4 },
    ],
    [12],
    [13],
  )
  expect(gapped).toMatchObject({ ok: false, message: CONDITION_MESSAGES.contiguous })
})

test('a condition can be inserted when every position is already occupied', () => {
  const atStart = [notify, record].map((item) => ({ ...item, position: item.position - 1 }))
  expect(placeCondition(atStart, [12], [13])).toEqual({
    ok: true,
    position: 1,
    trueSteps: [12],
    falseSteps: [13],
  })
  expect(
    placeCondition(
      [step(1, 1, 'VALIDATE_FIELDS', { requiredFields: ['email'] }, 'Prefix'), notify, record],
      [12],
      [13],
    ),
  ).toEqual({ ok: true, position: 2, trueSteps: [12], falseSteps: [13] })
})

test('branch steps that already belong to a condition cannot be reused', () => {
  const saved = condition([12], [13])
  expect(placeCondition([saved, notify, record], [12], [])).toMatchObject({
    ok: false,
    message: CONDITION_MESSAGES.shared,
  })
  expect(
    placeCondition(
      [notify, step(20, 3, 'CONDITION', { field: 'ready', operator: 'EXISTS', trueSteps: [12], falseSteps: [] })],
      [20],
      [],
    ),
  ).toMatchObject({ ok: false, message: CONDITION_MESSAGES.nested })
})

test('moves inside a branch are allowed and moves that mix branches are blocked', () => {
  const audit = step(14, 3, 'VALIDATE_FIELDS', { requiredFields: ['ready'] }, 'Audit')
  const later = step(13, 4, 'HTTP_REQUEST', { method: 'GET', url: 'https://example.com/hook' }, 'Record')
  const finish = step(15, 5, 'VALIDATE_FIELDS', { requiredFields: ['ready'] }, 'Finish')
  const saved = [condition([12, 14], [13]), notify, audit, later, finish]
  const [amount, first, second, falseStep, tail] = saved
  expect(moveBreaksBranch(saved, 2, -1)).toBeNull()
  expect(reorderKeepsBranches([amount, second, first, falseStep, tail])).toBe(true)
  expect(branchesFollowCondition([amount, second, first, falseStep, tail])).toBe(false)
  expect(moveBreaksBranch(saved, 2, 1)).toBe(CONDITION_MESSAGES.move)
  expect(moveBreaksBranch(saved, 3, 1)).toBe(CONDITION_MESSAGES.move)
  expect(moveBreaksBranch(saved, 4, -1)).toBe(CONDITION_MESSAGES.move)
})

test('deleting a referenced branch step is blocked and deleting the condition is not', () => {
  const saved = [
    condition([12], [13]),
    notify,
    record,
    step(15, 4, 'VALIDATE_FIELDS', { requiredFields: ['ready'] }, 'Finish'),
  ]
  expect(deletionBreaksBranch(saved, 12)).toBe(CONDITION_MESSAGES.delete)
  expect(deletionBreaksBranch(saved, 10)).toBeNull()
  expect(deletionBreaksBranch(saved, 15)).toBeNull()
})

test('a free position inside a branch is blocked and a position after the branch is not', () => {
  const saved = [
    condition([12], [13]),
    notify,
    { ...record, position: 4 },
  ]
  expect(insertionBreaksBranch(saved, 3)).toBe(CONDITION_MESSAGES.insert)
  expect(insertionBreaksBranch(saved, 2)).toBeNull()
  expect(insertionBreaksBranch(saved, 5)).toBeNull()
})

test('the next saved step can extend the false branch, or the true branch when false is empty', () => {
  const finish = step(15, 4, 'VALIDATE_FIELDS', { requiredFields: ['ready'] }, 'Finish')
  const saved = [condition([12], [13]), notify, record, finish]
  expect(branchAppend(saved, 10, [12], [13])).toEqual({ step: finish, allowTrue: false })
  const openFalse = [condition([12], []), notify, record]
  expect(branchAppend(openFalse, 10, [12], [])).toEqual({ step: record, allowTrue: true })
  expect(branchAppend(saved, 10, [12], [13, 15])).toBeNull()
})
