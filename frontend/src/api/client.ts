import { ApiError, isAbort } from './errors.ts'
import {
  parseExecution,
  parseExecutionPage,
  parseStepList,
  parseWorkflow,
  parseWorkflowList,
  parseWorkflowSteps,
} from './parse.ts'
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

function problemMessage(value: unknown, status: number): string {
  if (value && typeof value === 'object') {
    const record = value as { detail?: unknown; message?: unknown }
    if (typeof record.detail === 'string' && record.detail.length > 0) {
      return record.detail
    }
    if (typeof record.message === 'string' && record.message.length > 0) {
      return record.message
    }
  }
  return `Request failed (${status})`
}

async function request(path: string, signal?: AbortSignal): Promise<unknown> {
  let response: Response

  try {
    response = await fetch(apiUrl(path), {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch (error) {
    if (isAbort(error)) {
      throw error
    }
    throw new ApiError(0, 'The API could not be reached')
  }

  const body = await readBody(response)

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

export function listWorkflows(signal?: AbortSignal): Promise<Workflow[]> {
  return request('/api/workflows', signal).then(parseWorkflowList)
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
