import { ApiError } from './errors.ts'
import type {
  ExecutionStatus,
  PageResponse,
  WorkflowExecution,
} from '../types/execution.ts'
import { EXECUTION_STATUSES } from '../types/execution.ts'
import type { ExecutionStep, StepHistoryStatus } from '../types/stepHistory.ts'
import type { DashboardOverview, RecentExecution } from '../types/dashboard.ts'
import type { Workflow, WorkflowStep } from '../types/workflow.ts'

const STEP_STATUSES = [
  'NOT_STARTED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
] as const satisfies readonly StepHistoryStatus[]

const STEP_TYPES = ['VALIDATE_FIELDS', 'HTTP_REQUEST'] as const

function unexpected(): never {
  throw new ApiError(0, 'The API returned an unexpected response')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredNumber(record: Record<string, unknown>, key: string): number {
  const value = record[key]
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    unexpected()
  }
  return value
}

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key]
  if (typeof value !== 'string' || value.length === 0) {
    unexpected()
  }
  return value
}

function nullableString(
  record: Record<string, unknown>,
  key: string,
): string | null {
  const value = record[key]
  if (!Object.hasOwn(record, key) || value == null) {
    return null
  }
  if (typeof value !== 'string') {
    unexpected()
  }
  return value
}

function oneOf<T extends string>(
  value: unknown,
  allowed: readonly T[],
): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) {
    unexpected()
  }
  return value as T
}

export function parseWorkflow(value: unknown): Workflow {
  if (!isRecord(value) || typeof value.active !== 'boolean') {
    unexpected()
  }
  return {
    id: requiredNumber(value, 'id'),
    name: requiredString(value, 'name'),
    description: nullableString(value, 'description'),
    active: value.active,
    createdAt: requiredString(value, 'createdAt'),
    stepRevision: revisionNumber(value.stepRevision),
  }
}

function revisionNumber(value: unknown): number {
  if (value == null) {
    return 0
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    unexpected()
  }
  return value
}

export function parseWorkflowStep(value: unknown): WorkflowStep {
  if (!isRecord(value) || !isRecord(value.configuration)) {
    unexpected()
  }
  return {
    id: requiredNumber(value, 'id'),
    name: requiredString(value, 'name'),
    stepType: oneOf(value.stepType, STEP_TYPES),
    position: requiredNumber(value, 'position'),
    configuration: value.configuration,
  }
}

export function parseExecution(value: unknown): WorkflowExecution {
  if (!isRecord(value)) {
    unexpected()
  }
  return {
    id: requiredNumber(value, 'id'),
    workflowId: requiredNumber(value, 'workflowId'),
    status: oneOf<ExecutionStatus>(value.status, EXECUTION_STATUSES),
    createdAt: requiredString(value, 'createdAt'),
    failureReason: nullableString(value, 'failureReason'),
  }
}

export function parseStep(value: unknown): ExecutionStep {
  if (!isRecord(value)) {
    unexpected()
  }
  const attempt = value.attemptNumber
  if (
    attempt != null &&
    (typeof attempt !== 'number' || !Number.isSafeInteger(attempt))
  ) {
    unexpected()
  }
  return {
    stepId: requiredNumber(value, 'stepId'),
    name: requiredString(value, 'name'),
    stepType: oneOf(value.stepType, STEP_TYPES),
    position: requiredNumber(value, 'position'),
    status: oneOf<StepHistoryStatus>(value.status, STEP_STATUSES),
    attemptNumber: attempt ?? null,
    failureReason: nullableString(value, 'failureReason'),
    startedAt: nullableString(value, 'startedAt'),
    updatedAt: nullableString(value, 'updatedAt'),
    leaseUntil: nullableString(value, 'leaseUntil'),
  }
}

export function parseExecutionPage(value: unknown): PageResponse<WorkflowExecution> {
  return parsePage(value, parseExecution)
}

export function parseWorkflowPage(value: unknown): PageResponse<Workflow> {
  return parsePage(value, parseWorkflow)
}

export function parseRecentExecution(value: unknown): RecentExecution {
  if (!isRecord(value)) {
    unexpected()
  }
  return {
    id: requiredNumber(value, 'id'),
    workflowId: requiredNumber(value, 'workflowId'),
    workflowName: requiredString(value, 'workflowName'),
    status: oneOf<ExecutionStatus>(value.status, EXECUTION_STATUSES),
    createdAt: requiredString(value, 'createdAt'),
    failureReason: nullableString(value, 'failureReason'),
  }
}

export function parseRecentExecutionPage(
  value: unknown,
): PageResponse<RecentExecution> {
  return parsePage(value, parseRecentExecution)
}

export function parseDashboardOverview(value: unknown): DashboardOverview {
  if (!isRecord(value)) {
    unexpected()
  }
  return {
    totalWorkflows: requiredNumber(value, 'totalWorkflows'),
    activeWorkflows: requiredNumber(value, 'activeWorkflows'),
    totalExecutions: requiredNumber(value, 'totalExecutions'),
    succeededExecutions: requiredNumber(value, 'succeededExecutions'),
    failedExecutions: requiredNumber(value, 'failedExecutions'),
    successRate: nullableRate(value),
    windowDays: requiredNumber(value, 'windowDays'),
  }
}

function nullableRate(record: Record<string, unknown>): number | null {
  if (!Object.hasOwn(record, 'successRate') || record.successRate == null) {
    return null
  }
  if (typeof record.successRate !== 'number' || !Number.isFinite(record.successRate)) {
    unexpected()
  }
  return record.successRate
}

function parsePage<T>(value: unknown, parseItem: (item: unknown) => T): PageResponse<T> {
  if (!isRecord(value) || !Array.isArray(value.content)) {
    unexpected()
  }
  const content = value.content.map(parseItem)
  return {
    content,
    totalElements: requiredNumber(value, 'totalElements'),
    totalPages: requiredNumber(value, 'totalPages'),
    size: requiredNumber(value, 'size'),
    number: requiredNumber(value, 'number'),
    empty: typeof value.empty === 'boolean' ? value.empty : content.length === 0,
  }
}

export function parseStepList(value: unknown): ExecutionStep[] {
  if (!Array.isArray(value)) {
    unexpected()
  }
  return value
    .map(parseStep)
    .sort((left, right) => left.position - right.position || left.stepId - right.stepId)
}

export function parseWorkflowSteps(value: unknown): WorkflowStep[] {
  if (!Array.isArray(value)) {
    unexpected()
  }
  return value
    .map(parseWorkflowStep)
    .sort((left, right) => left.position - right.position || left.id - right.id)
}
