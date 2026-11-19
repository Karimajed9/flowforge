import { clearCsrf, csrfHeaders } from '../auth/csrf.ts'
import { noteSignedOut } from '../auth/session.ts'
import { ApiError, isAbort } from './errors.ts'
import {
  parseDashboardOverview,
  parseExecution,
  parseExecutionPage,
  parseRecentExecutionPage,
  parseStepList,
  parseWorkflow,
  parseWorkflowPage,
  parseWorkflowStep,
  parseWorkflowSteps,
} from './parse.ts'
import type { DashboardOverview, RecentExecution } from '../types/dashboard.ts'
import type { PageResponse, WorkflowExecution } from '../types/execution.ts'
import type { ExecutionStep } from '../types/stepHistory.ts'
import type { Workflow, WorkflowStep } from '../types/workflow.ts'

export { ApiError } from './errors.ts'

export interface ExecutionDetails {
  execution: WorkflowExecution
  steps: ExecutionStep[]
}

export interface WorkflowHistory {
  workflow: Workflow
  executions: PageResponse<WorkflowExecution>
}

export interface WorkflowDetail {
  workflow: Workflow
  steps: WorkflowStep[]
}

function apiUrl(path: string): string {
  const configured = import.meta.env.VITE_API_BASE_URL?.trim() ?? ''
  return `${configured.replace(/\/$/, '')}${path}`
}

async function readBody(response: Response): Promise<
  { parsed: true; value: unknown } | { parsed: false }
> {
  const text = await response.text()
  if (text.trim() === '') {
    return { parsed: true, value: null }
  }
  try {
    return { parsed: true, value: JSON.parse(text) as unknown }
  } catch {
    return { parsed: false }
  }
}

function textMessage(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function problemMessage(
  value: unknown,
  status: number,
): { message: string; title: string | null } {
  if (!value || typeof value !== 'object') {
    return { message: `Request failed (${status})`, title: null }
  }
  const record = value as {
    title?: unknown
    detail?: unknown
    message?: unknown
    errors?: unknown
  }
  const parts: string[] = []
  const summary = textMessage(record.detail) ?? textMessage(record.message)
  if (summary) {
    parts.push(summary)
  }
  if (Array.isArray(record.errors)) {
    for (const error of record.errors) {
      if (typeof error === 'string' && error.length > 0) {
        parts.push(error)
        continue
      }
      if (error && typeof error === 'object') {
        const item = error as {
          defaultMessage?: unknown
          message?: unknown
          detail?: unknown
        }
        const text =
          textMessage(item.defaultMessage) ??
          textMessage(item.message) ??
          textMessage(item.detail)
        if (text) {
          parts.push(text)
        }
      }
    }
  }
  return {
    message: parts.length > 0 ? parts.join(' ') : `Request failed (${status})`,
    title: textMessage(record.title),
  }
}

async function request(
  path: string,
  signal?: AbortSignal,
  method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE' = 'GET',
  payload?: unknown,
  options?: { rawBody?: string; headers?: Record<string, string> },
): Promise<unknown> {
  let response: Response
  const headers: Record<string, string> = { Accept: 'application/json' }
  const rawBody = options?.rawBody
  if (payload !== undefined || rawBody !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (method !== 'GET') {
    Object.assign(headers, await csrfHeaders())
    if (signal?.aborted) {
      throw new DOMException('Aborted', 'AbortError')
    }
  }
  if (options?.headers) {
    Object.assign(headers, options.headers)
  }

  try {
    response = await fetch(apiUrl(path), {
      method,
      credentials: 'include',
      headers,
      body:
        rawBody !== undefined
          ? rawBody
          : payload === undefined
            ? undefined
            : JSON.stringify(payload),
      signal,
    })
  } catch (error) {
    if (isAbort(error)) {
      throw error
    }
    throw new ApiError(0, 'The API could not be reached')
  }

  const body = await readBody(response)

  if (response.status === 401) {
    noteSignedOut()
  } else if (response.status === 403 && method !== 'GET') {
    clearCsrf()
  }

  if (!response.ok) {
    const problem = body.parsed
      ? problemMessage(body.value, response.status)
      : { message: `Request failed (${response.status})`, title: null }
    throw new ApiError(response.status, problem.message, problem.title)
  }

  if (!body.parsed) {
    throw new ApiError(response.status, 'The API returned invalid JSON')
  }

  return body.value
}

const PAGE_SIZE = 20

export function listWorkflows(
  page: number,
  query: string,
  active: boolean | null,
  signal?: AbortSignal,
): Promise<PageResponse<Workflow>> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PAGE_SIZE),
  })
  if (query !== '') {
    params.set('q', query)
  }
  if (active !== null) {
    params.set('active', String(active))
  }
  return request(`/api/workflows?${params}`, signal).then(parseWorkflowPage)
}

export function getDashboardOverview(
  signal?: AbortSignal,
): Promise<DashboardOverview> {
  return request('/api/dashboard/overview', signal).then(parseDashboardOverview)
}

export function listRecentExecutions(
  page: number,
  signal?: AbortSignal,
): Promise<PageResponse<RecentExecution>> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PAGE_SIZE),
  })
  return request(`/api/executions?${params}`, signal).then(parseRecentExecutionPage)
}

export function getWorkflow(
  id: number,
  signal?: AbortSignal,
): Promise<Workflow> {
  return request(`/api/workflows/${id}`, signal).then(parseWorkflow)
}

export function listWorkflowSteps(
  workflowId: number,
  signal?: AbortSignal,
): Promise<WorkflowStep[]> {
  return request(`/api/workflows/${workflowId}/steps`, signal).then(
    parseWorkflowSteps,
  )
}

export function listWorkflowExecutions(
  workflowId: number,
  page: number,
  signal?: AbortSignal,
): Promise<PageResponse<WorkflowExecution>> {
  const params = new URLSearchParams({
    page: String(page),
    size: '20',
  })
  return request(
    `/api/workflows/${workflowId}/executions?${params.toString()}`,
    signal,
  ).then(parseExecutionPage)
}

export function getExecution(
  id: number,
  signal?: AbortSignal,
): Promise<WorkflowExecution> {
  return request(`/api/executions/${id}`, signal).then(parseExecution)
}

export function getExecutionSteps(
  id: number,
  signal?: AbortSignal,
): Promise<ExecutionStep[]> {
  return request(`/api/executions/${id}/steps`, signal).then(parseStepList)
}

export async function loadExecutionDetails(
  id: number,
  signal?: AbortSignal,
): Promise<ExecutionDetails> {
  const [execution, steps] = await Promise.all([
    getExecution(id, signal),
    getExecutionSteps(id, signal),
  ])
  return { execution, steps }
}

export function runWorkflow(
  workflowId: number,
  jsonText: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<WorkflowExecution> {
  return request(`/api/workflows/${workflowId}/executions`, signal, 'POST', undefined, {
    rawBody: jsonText,
    headers: { 'Idempotency-Key': idempotencyKey },
  }).then(parseExecution)
}

export function createWorkflow(
  input: { name: string; description: string | null },
  signal?: AbortSignal,
): Promise<Workflow> {
  return request('/api/workflows', signal, 'POST', input).then(parseWorkflow)
}

export function updateWorkflowStatus(
  id: number,
  active: boolean,
  signal?: AbortSignal,
): Promise<Workflow> {
  return request(`/api/workflows/${id}/status`, signal, 'PATCH', { active }).then(
    parseWorkflow,
  )
}

export function updateWorkflowStep(
  workflowId: number,
  stepId: number,
  input: {
    name: string
    configuration: Record<string, unknown>
    revision: number
  },
  signal?: AbortSignal,
): Promise<{ step: WorkflowStep; stepRevision: number }> {
  return request(
    `/api/workflows/${workflowId}/steps/${stepId}`,
    signal,
    'PATCH',
    input,
  ).then(parseStepChange)
}

export function deleteWorkflowStep(
  workflowId: number,
  stepId: number,
  revision: number,
  signal?: AbortSignal,
): Promise<{ steps: WorkflowStep[]; stepRevision: number }> {
  return request(
    `/api/workflows/${workflowId}/steps/${stepId}?revision=${revision}`,
    signal,
    'DELETE',
  ).then(parseStepListChange)
}

export function reorderWorkflowSteps(
  workflowId: number,
  input: { stepIds: number[]; revision: number },
  signal?: AbortSignal,
): Promise<{ steps: WorkflowStep[]; stepRevision: number }> {
  return request(
    `/api/workflows/${workflowId}/steps/order`,
    signal,
    'PUT',
    input,
  ).then(parseStepListChange)
}

function parseStepChange(value: unknown): { step: WorkflowStep; stepRevision: number } {
  if (!value || typeof value !== 'object') {
    throw new ApiError(0, 'The API returned an unexpected response')
  }
  const record = value as { step?: unknown; stepRevision?: unknown }
  return {
    step: parseWorkflowStep(record.step),
    stepRevision: requiredRevision(record.stepRevision),
  }
}

function parseStepListChange(value: unknown): {
  steps: WorkflowStep[]
  stepRevision: number
} {
  if (!value || typeof value !== 'object') {
    throw new ApiError(0, 'The API returned an unexpected response')
  }
  const record = value as { steps?: unknown; stepRevision?: unknown }
  return {
    steps: parseWorkflowSteps(record.steps),
    stepRevision: requiredRevision(record.stepRevision),
  }
}

function requiredRevision(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new ApiError(0, 'The API returned an unexpected response')
  }
  return value
}

export function createWorkflowStep(
  workflowId: number,
  input: {
    name: string
    stepType: WorkflowStep['stepType']
    position: number
    configuration: Record<string, unknown>
    revision?: number
  },
  signal?: AbortSignal,
): Promise<WorkflowStep> {
  const { revision, ...body } = input
  const path =
    typeof revision === 'number'
      ? `/api/workflows/${workflowId}/steps?revision=${revision}`
      : `/api/workflows/${workflowId}/steps`
  return request(path, signal, 'POST', body).then(parseWorkflowStep)
}

export async function loadWorkflowDetail(
  workflowId: number,
  signal?: AbortSignal,
): Promise<WorkflowDetail> {
  const [workflow, steps] = await Promise.all([
    getWorkflow(workflowId, signal),
    listWorkflowSteps(workflowId, signal),
  ])
  return { workflow, steps }
}

export async function loadWorkflowHistory(
  workflowId: number,
  page: number,
  signal?: AbortSignal,
): Promise<WorkflowHistory> {
  const [workflow, executions] = await Promise.all([
    getWorkflow(workflowId, signal),
    listWorkflowExecutions(workflowId, page, signal),
  ])
  return { workflow, executions }
}
