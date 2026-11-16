import type { ExecutionStatus } from './execution.ts'

export interface DashboardOverview {
  totalWorkflows: number
  activeWorkflows: number
  totalExecutions: number
  succeededExecutions: number
  failedExecutions: number
  successRate: number | null
  windowDays: number
}

export interface RecentExecution {
  id: number
  workflowId: number
  workflowName: string
  status: ExecutionStatus
  createdAt: string
  failureReason: string | null
}
