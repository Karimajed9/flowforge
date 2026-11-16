import {
  listWorkflows,
  loadExecutionDetails,
  loadWorkflowHistory,
} from './client.ts'
import { useLoad } from './useLoad.ts'

export function useWorkflowList() {
  return useLoad('workflows', listWorkflows)
}

export function useExecutionDetails(executionId: number | null) {
  return useLoad(
    executionId === null ? null : `execution:${executionId}`,
    (signal) => {
      if (executionId === null) {
        return Promise.reject(new Error('Missing execution'))
      }
      return loadExecutionDetails(executionId, signal)
    },
  )
}

export function useWorkflowHistory(workflowId: number | null, page: number) {
  return useLoad(
    workflowId === null ? null : `workflow-history:${workflowId}:${page}`,
    (signal) => {
      if (workflowId === null) {
        return Promise.reject(new Error('Missing workflow'))
      }
      return loadWorkflowHistory(workflowId, page, signal)
    },
  )
}
