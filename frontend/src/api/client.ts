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

function problemMessage(value: unknown, status: number): string {
  if (!value || typeof value !== 'object') {
    return `Request failed (${status})`
  }
  const record = value as {
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
  return parts.length > 0 ? parts.join(' ') : `Request failed (${status})`
}

async function request(
  path: string,
  signal?: AbortSignal,
  method: 'GET' | 'POST' | 'PATCH' = 'GET',
  payload?: unknown,
): Promise<unknown> {
  let response: Response
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (payload !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (method !== 'GET') {
    Object.assign(headers, await csrfHeaders())
    if (signal?.aborted) {
      throw new DOMException('Aborted', 'AbortError')
    }
  }

  try {
    response = await fetch(apiUrl(path), {
      method,
      credentials: 'include',
      headers,
      body: payload === undefined ? undefined : JSON.stringify(payload),
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
    throw new ApiError(
      response.status,
      body.parsed
        ? problemMessage(body.value, response.status)
        : `Request failed (${response.status})`,
    )
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

export function createWorkflowStep(
  workflowId: number,
  input: {
    name: string
    stepType: WorkflowStep['stepType']
    position: number
    configuration: Record<string, unknown>
  },
  signal?: AbortSignal,
): Promise<WorkflowStep> {
  return request(
    `/api/workflows/${workflowId}/steps`,
    signal,
    'POST',
    input,
  ).then(parseWorkflowStep)
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
