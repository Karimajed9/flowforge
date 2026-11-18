import { ApiError } from '../api/errors.ts'

export const POSITION_TAKEN = 'A step already uses this position. Choose another one.'

export const STALE_ELSEWHERE =
  'This workflow was changed elsewhere. Refresh the page and try again.'

const POSITION_TITLE = 'Workflow step position conflict'
const POSITION_DETAIL = 'A step already exists at this position'

export function usableReason(message: string): string | null {
  const text = message.trim()
  if (!text || /^Request failed \(\d+\)$/.test(text)) {
    return null
  }
  return text
}

export function isPositionConflict(error: ApiError): boolean {
  return (
    error.status === 409 &&
    (error.title === POSITION_TITLE || error.message === POSITION_DETAIL)
  )
}

export function editorMutationMessage(error: ApiError, fallback: string): string {
  if (isPositionConflict(error)) {
    return POSITION_TAKEN
  }
  const reason = usableReason(error.message)
  if (error.status === 409 && !reason) {
    return STALE_ELSEWHERE
  }
  return reason ?? fallback
}
