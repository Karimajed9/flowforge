import type { WorkflowStep } from '../types/workflow.ts'

export const CONDITION_OPERATORS = [
  'EQUALS',
  'NOT_EQUALS',
  'GREATER_THAN',
  'LESS_THAN',
  'EXISTS',
] as const

export type ConditionOperator = (typeof CONDITION_OPERATORS)[number]

export type ComparisonKind = 'string' | 'number' | 'boolean' | 'null'

export type ConditionDraft = {
  field: string
  operator: ConditionOperator
  valueKind: ComparisonKind
  valueText: string
  boolValue: boolean
}

export const CONDITION_MESSAGES = {
  field: 'Condition field must be a top-level name',
  operator: 'Condition operator is invalid',
  valueRequired: 'Condition value is required',
  valueNumber: 'Condition value must be a number',
  valueType: 'Condition value must be a string, number, boolean, or null',
  branchRequired: 'A condition needs at least one branch step',
  branchIds: 'Branch steps must be positive integers',
  duplicate: 'A branch lists the same step twice',
  unknown: 'A branch step is unknown',
  nested: 'A branch cannot contain a condition',
  shared: 'A step is in more than one branch',
  trueBeforeFalse: 'True branch steps must come before the false branch.',
  contiguous: 'These steps must be one contiguous block, true branch first.',
  span: 'Branch steps must be the contiguous steps after the condition',
  move: 'Branch steps must stay together immediately after the condition, with the true branch before the false branch.',
  delete:
    'This step belongs to a condition branch. Deleting it would break that condition, and one save cannot update both.',
  insert: 'Creating a step at that position would split the condition branch.',
} as const

const PLAIN_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/

export function isConditionOperator(value: unknown): value is ConditionOperator {
  return (
    typeof value === 'string' &&
    (CONDITION_OPERATORS as readonly string[]).includes(value)
  )
}

export function emptyConditionDraft(): ConditionDraft {
  return {
    field: '',
    operator: 'EQUALS',
    valueKind: 'string',
    valueText: '',
    boolValue: true,
  }
}

export function readCondition(configuration: Record<string, unknown>): ConditionDraft {
  const field = typeof configuration.field === 'string' ? configuration.field : ''
  const operator = isConditionOperator(configuration.operator)
    ? configuration.operator
    : 'EQUALS'
  if (operator === 'EXISTS') {
    return { field, operator, valueKind: 'null', valueText: '', boolValue: true }
  }
  const value = configuration.value
  if (typeof value === 'number' && Number.isFinite(value)) {
    return {
      field,
      operator,
      valueKind: 'number',
      valueText: JSON.stringify(value),
      boolValue: true,
    }
  }
  if (typeof value === 'boolean') {
    return { field, operator, valueKind: 'boolean', valueText: '', boolValue: value }
  }
  if (value === null) {
    return { field, operator, valueKind: 'null', valueText: '', boolValue: true }
  }
  if (typeof value === 'string') {
    return { field, operator, valueKind: 'string', valueText: value, boolValue: true }
  }
  return { field, operator, valueKind: 'string', valueText: '', boolValue: true }
}

export function readBranchIds(configuration: Record<string, unknown>): {
  trueSteps: number[]
  falseSteps: number[]
} | null {
  const trueSteps = positiveIds(configuration.trueSteps)
  const falseSteps = positiveIds(configuration.falseSteps)
  if (!trueSteps || !falseSteps) {
    return null
  }
  return { trueSteps, falseSteps }
}

export function buildConditionConfiguration(
  draft: ConditionDraft,
  trueSteps: number[],
  falseSteps: number[],
):
  | { ok: true; configuration: Record<string, unknown> }
  | { ok: false; message: string } {
  const field = draft.field.trim()
  if (!isTopLevelField(field)) {
    return { ok: false, message: CONDITION_MESSAGES.field }
  }
  if (!isConditionOperator(draft.operator)) {
    return { ok: false, message: CONDITION_MESSAGES.operator }
  }
  if (!positiveIds(trueSteps) || !positiveIds(falseSteps)) {
    return { ok: false, message: CONDITION_MESSAGES.branchIds }
  }
  if (trueSteps.length + falseSteps.length === 0) {
    return { ok: false, message: CONDITION_MESSAGES.branchRequired }
  }
  const configuration: Record<string, unknown> = {
    field,
    operator: draft.operator,
    trueSteps: [...trueSteps],
    falseSteps: [...falseSteps],
  }
  if (draft.operator === 'EXISTS') {
    return { ok: true, configuration }
  }
  const value = comparisonValue(draft)
  if (!value.ok) {
    return value
  }
  configuration.value = value.value
  return { ok: true, configuration }
}

export function conditionDraftDiffers(
  step: WorkflowStep,
  name: string,
  draft: ConditionDraft,
  trueSteps: number[],
  falseSteps: number[],
): boolean {
  if (name.trim() !== step.name.trim()) {
    return true
  }
  const built = buildConditionConfiguration(draft, trueSteps, falseSteps)
  if (!built.ok) {
    return true
  }
  return !sameJson(built.configuration, step.configuration)
}

export function formatConditionValue(value: unknown): string {
  if (
    typeof value === 'string' ||
    typeof value === 'number' ||
    typeof value === 'boolean' ||
    value === null
  ) {
    return JSON.stringify(value)
  }
  return ''
}

export function savedOrder(steps: WorkflowStep[]): WorkflowStep[] {
  return [...steps].sort(
    (left, right) => left.position - right.position || left.id - right.id,
  )
}

export function branchMemberIds(steps: WorkflowStep[]): Set<number> {
  const ids = new Set<number>()
  for (const step of steps) {
    if (step.stepType !== 'CONDITION') {
      continue
    }
    const branches = readBranchIds(step.configuration)
    if (!branches) {
      continue
    }
    for (const id of [...branches.trueSteps, ...branches.falseSteps]) {
      ids.add(id)
    }
  }
  return ids
}

export function branchRole(
  steps: WorkflowStep[],
  stepId: number,
): 'true' | 'false' | null {
  for (const step of savedOrder(steps)) {
    if (step.stepType !== 'CONDITION') {
      continue
    }
    const branches = readBranchIds(step.configuration)
    if (!branches) {
      continue
    }
    if (branches.trueSteps.includes(stepId)) {
      return 'true'
    }
    if (branches.falseSteps.includes(stepId)) {
      return 'false'
    }
  }
  return null
}

export function placeCondition(
  steps: WorkflowStep[],
  trueIds: number[],
  falseIds: number[],
):
  | { ok: true; position: number; trueSteps: number[]; falseSteps: number[] }
  | { ok: false; message: string } {
  if (trueIds.length + falseIds.length === 0) {
    return { ok: false, message: CONDITION_MESSAGES.branchRequired }
  }
  const listed = [...trueIds, ...falseIds]
  if (new Set(listed).size !== listed.length) {
    return { ok: false, message: CONDITION_MESSAGES.duplicate }
  }
  const ordered = savedOrder(steps)
  const byId = new Map(ordered.map((step) => [step.id, step]))
  const claimed = branchMemberIds(steps)
  for (const id of listed) {
    const step = byId.get(id)
    if (!step) {
      return { ok: false, message: CONDITION_MESSAGES.unknown }
    }
    if (step.stepType === 'CONDITION') {
      return { ok: false, message: CONDITION_MESSAGES.nested }
    }
    if (claimed.has(id)) {
      return { ok: false, message: CONDITION_MESSAGES.shared }
    }
  }
  const trueSteps = sortByPosition(trueIds, byId)
  const falseSteps = sortByPosition(falseIds, byId)
  if (trueSteps.length > 0 && falseSteps.length > 0) {
    const trueMax = positionOf(trueSteps[trueSteps.length - 1], byId)
    const falseMin = positionOf(falseSteps[0], byId)
    if (trueMax >= falseMin) {
      return { ok: false, message: CONDITION_MESSAGES.trueBeforeFalse }
    }
  }
  const orderedIds = [...trueSteps, ...falseSteps]
  const first = byId.get(orderedIds[0] ?? -1)
  if (!first) {
    return { ok: false, message: CONDITION_MESSAGES.unknown }
  }
  const start = ordered.findIndex((step) => step.id === first.id)
  const slice = ordered.slice(start, start + orderedIds.length)
  if (
    slice.length !== orderedIds.length ||
    slice.some((step, index) => step.id !== orderedIds[index])
  ) {
    return { ok: false, message: CONDITION_MESSAGES.contiguous }
  }
  const preview = ordered.slice()
  preview.splice(start, 0, {
    id: -1,
    name: 'condition',
    stepType: 'VALIDATE_FIELDS',
    position: first.position,
    configuration: {},
  })
  if (!branchesFollowCondition(preview)) {
    return { ok: false, message: CONDITION_MESSAGES.insert }
  }
  return { ok: true, position: first.position, trueSteps, falseSteps }
}

export function branchesFollowCondition(ordered: WorkflowStep[]): boolean {
  return layoutAllows(ordered, false)
}

export function reorderKeepsBranches(ordered: WorkflowStep[]): boolean {
  return layoutAllows(ordered, true)
}

export function moveBreaksBranch(
  steps: WorkflowStep[],
  index: number,
  direction: -1 | 1,
): string | null {
  const nextIndex = index + direction
  if (nextIndex < 0 || nextIndex >= steps.length) {
    return null
  }
  const order = steps.slice()
  const current = order[index]
  const neighbor = order[nextIndex]
  if (!current || !neighbor) {
    return null
  }
  order[index] = neighbor
  order[nextIndex] = current
  return reorderKeepsBranches(order) ? null : CONDITION_MESSAGES.move
}

export function deletionBreaksBranch(
  steps: WorkflowStep[],
  stepId: number,
): string | null {
  const remaining = savedOrder(steps.filter((step) => step.id !== stepId))
  return branchesFollowCondition(remaining) ? null : CONDITION_MESSAGES.delete
}

export function insertionBreaksBranch(
  steps: WorkflowStep[],
  position: number,
): string | null {
  if (steps.some((step) => step.position === position)) {
    return null
  }
  const probe: WorkflowStep = {
    id: -1,
    name: 'new step',
    stepType: 'VALIDATE_FIELDS',
    position,
    configuration: {},
  }
  return branchesFollowCondition(savedOrder([...steps, probe]))
    ? null
    : CONDITION_MESSAGES.insert
}

export function branchAppend(
  steps: WorkflowStep[],
  conditionId: number,
  trueSteps: number[],
  falseSteps: number[],
): { step: WorkflowStep; allowTrue: boolean } | null {
  const ordered = savedOrder(steps)
  const index = ordered.findIndex((step) => step.id === conditionId)
  const condition = ordered[index]
  if (!condition || condition.stepType !== 'CONDITION') {
    return null
  }
  const listed = [...trueSteps, ...falseSteps]
  if (listed.length === 0 || new Set(listed).size !== listed.length) {
    return null
  }
  const following = ordered.slice(index + 1, index + 1 + listed.length)
  if (
    following.length !== listed.length ||
    following.some((step, offset) => step.id !== listed[offset])
  ) {
    return null
  }
  const next = ordered[index + 1 + listed.length]
  if (!next || next.stepType === 'CONDITION' || branchMemberIds(steps).has(next.id)) {
    return null
  }
  return { step: next, allowTrue: falseSteps.length === 0 }
}

function layoutAllows(ordered: WorkflowStep[], allowReorder: boolean): boolean {
  const used = new Set<number>()
  for (let index = 0; index < ordered.length; index += 1) {
    const step = ordered[index]
    if (!step || step.stepType !== 'CONDITION') {
      continue
    }
    const branches = readBranchIds(step.configuration)
    if (!branches) {
      return false
    }
    const listed = [...branches.trueSteps, ...branches.falseSteps]
    if (listed.length === 0 || new Set(listed).size !== listed.length) {
      return false
    }
    for (const id of listed) {
      if (used.has(id) || id === step.id) {
        return false
      }
      used.add(id)
    }
    if (index + listed.length >= ordered.length) {
      return false
    }
    const following = ordered.slice(index + 1, index + 1 + listed.length)
    if (following.some((item) => item.stepType === 'CONDITION')) {
      return false
    }
    const trueActual = following.slice(0, branches.trueSteps.length).map((item) => item.id)
    const falseActual = following.slice(branches.trueSteps.length).map((item) => item.id)
    if (
      !sameIds(trueActual, branches.trueSteps) ||
      !sameIds(falseActual, branches.falseSteps)
    ) {
      return false
    }
    if (
      !allowReorder &&
      (!sameOrder(trueActual, branches.trueSteps) ||
        !sameOrder(falseActual, branches.falseSteps))
    ) {
      return false
    }
  }
  return true
}

function comparisonValue(
  draft: ConditionDraft,
): { ok: true; value: unknown } | { ok: false; message: string } {
  const numeric =
    draft.operator === 'GREATER_THAN' ||
    draft.operator === 'LESS_THAN' ||
    draft.valueKind === 'number'
  if (numeric) {
    if (draft.valueText.trim() === '') {
      return { ok: false, message: CONDITION_MESSAGES.valueRequired }
    }
    const value = parsePlainNumber(draft.valueText)
    if (value === null) {
      return { ok: false, message: CONDITION_MESSAGES.valueNumber }
    }
    return { ok: true, value }
  }
  if (draft.valueKind === 'boolean') {
    return { ok: true, value: draft.boolValue }
  }
  if (draft.valueKind === 'null') {
    return { ok: true, value: null }
  }
  if (draft.valueKind === 'string') {
    return { ok: true, value: draft.valueText }
  }
  return { ok: false, message: CONDITION_MESSAGES.valueType }
}

function parsePlainNumber(text: string): number | null {
  const trimmed = text.trim()
  if (!PLAIN_NUMBER.test(trimmed)) {
    return null
  }
  const value = Number(trimmed)
  return Number.isFinite(value) ? value : null
}

function positiveIds(value: unknown): number[] | null {
  if (!Array.isArray(value)) {
    return null
  }
  const ids: number[] = []
  for (const item of value) {
    if (typeof item !== 'number' || !Number.isSafeInteger(item) || item <= 0) {
      return null
    }
    ids.push(item)
  }
  return ids
}

function isTopLevelField(value: string): boolean {
  if (value.length === 0) {
    return false
  }
  for (const character of value) {
    if (/\s/.test(character) || character === '.' || character === '[' || character === ']') {
      return false
    }
  }
  return true
}

function sortByPosition(ids: number[], byId: Map<number, WorkflowStep>): number[] {
  return [...ids].sort((left, right) => {
    const leftStep = byId.get(left)
    const rightStep = byId.get(right)
    const leftPosition = leftStep?.position ?? 0
    const rightPosition = rightStep?.position ?? 0
    return leftPosition - rightPosition || left - right
  })
}

function positionOf(id: number | undefined, byId: Map<number, WorkflowStep>): number {
  if (id === undefined) {
    return 0
  }
  return byId.get(id)?.position ?? 0
}

function sameIds(left: number[], right: number[]): boolean {
  if (left.length !== right.length) {
    return false
  }
  return sameMembers(left, right)
}

function sameMembers(left: number[], right: number[]): boolean {
  const counts = new Map<number, number>()
  for (const id of left) {
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  for (const id of right) {
    const count = counts.get(id) ?? 0
    if (count === 0) {
      return false
    }
    counts.set(id, count - 1)
  }
  return true
}

function sameOrder(left: number[], right: number[]): boolean {
  return left.length === right.length && left.every((id, index) => id === right[index])
}

function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(canonicalize(left)) === JSON.stringify(canonicalize(right))
}

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(canonicalize)
  }
  if (value && typeof value === 'object') {
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      sorted[key] = canonicalize((value as Record<string, unknown>)[key])
    }
    return sorted
  }
  return value
}
