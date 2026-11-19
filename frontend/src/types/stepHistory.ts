export type StepHistoryStatus =
  | 'NOT_STARTED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'

export interface ExecutionStep {
  stepId: number
  name: string
  stepType: 'VALIDATE_FIELDS' | 'HTTP_REQUEST' | 'CONDITION'
  position: number
  status: StepHistoryStatus
  attemptNumber: number | null
  failureReason: string | null
  startedAt: string | null
  updatedAt: string | null
  leaseUntil: string | null
  branchTaken: boolean | null
}
