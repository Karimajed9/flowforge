import type { ExecutionStatus } from '../types/execution.ts'

const notes: Record<ExecutionStatus, string> = {
  RECEIVED:
    'This execution was accepted and has not finished. It is not a completed workflow.',
  QUEUED:
    'This execution is queued and has not finished. It is not a completed workflow.',
  RUNNING: 'This execution is running. It is not a completed workflow.',
  SUCCEEDED: 'This workflow run completed.',
  FAILED: 'This execution finished with a failure.',
}

export function executionStatusNote(status: string): string | null {
  if (status in notes) {
    return notes[status as ExecutionStatus]
  }
  return null
}
