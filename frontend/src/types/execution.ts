export const EXECUTION_STATUSES = [
  'RECEIVED',
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
] as const

export type ExecutionStatus = (typeof EXECUTION_STATUSES)[number]

export interface WorkflowExecution {
  id: number
  workflowId: number
  status: ExecutionStatus
  createdAt: string
  failureReason: string | null
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  empty: boolean
}
