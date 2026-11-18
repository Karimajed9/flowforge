import { workflowNotFound } from './notFoundCopy.ts'
import { ApiError } from '../api/errors.ts'

export const invalidJsonMessage =
  'Enter a valid JSON object. Check the commas, quotes, and braces.'

export const invalidRootMessage = 'The payload must be a JSON object.'

const inactiveDetail = 'Workflow is inactive'
const differentPayloadDetail =
  'Idempotency key was already used with a different payload'
const idempotencyTitle = 'Idempotency conflict'
const malformedDetail = 'Malformed JSON payload'
const objectRequiredDetail = 'A JSON object is required'
const tooLargeDetail = 'Payload is too large'

export type RunNotice = {
  message: string
  retry: boolean
}

export function parsePayload(text: string): { ok: true } | RunNotice & { ok: false } {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    return { ok: false, message: invalidJsonMessage, retry: false }
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, message: invalidRootMessage, retry: false }
  }
  return { ok: true }
}

const inactiveNotice: RunNotice = {
  message: 'This workflow is inactive. Activate it before running.',
  retry: false,
}

function namesInactive(error: ApiError): boolean {
  return error.message.includes(inactiveDetail) || error.title === inactiveDetail
}

export function runFailure(error: ApiError, workflowActive = true): RunNotice {
  if (error.status === 0) {
    return { message: 'The server cannot be reached.', retry: true }
  }
  if (error.status === 401) {
    return {
      message: 'Your session has expired. Please sign in again.',
      retry: false,
    }
  }
  if (error.status === 403) {
    return { message: 'The request was rejected.', retry: true }
  }
  if (error.status === 404) {
    return {
      message: `${workflowNotFound.title}. ${workflowNotFound.description}`,
      retry: false,
    }
  }
  if (error.status === 409 && namesInactive(error)) {
    return inactiveNotice
  }
  if (error.status === 409 && error.message === differentPayloadDetail) {
    return {
      message: 'This submission key was previously used with different data.',
      retry: false,
    }
  }
  if (error.status === 409 && error.title === idempotencyTitle) {
    return {
      message: 'This submission key was already used.',
      retry: false,
    }
  }
  if (error.status === 409 && workflowActive === false) {
    return inactiveNotice
  }
  if (error.status === 400 && error.message === malformedDetail) {
    return { message: invalidJsonMessage, retry: false }
  }
  if (error.status === 400 && error.message === objectRequiredDetail) {
    return { message: invalidRootMessage, retry: false }
  }
  if (
    error.status === 413 ||
    (error.status === 400 && error.message === tooLargeDetail)
  ) {
    return { message: 'The payload is too large.', retry: false }
  }
  if (error.status >= 500 && error.status <= 599) {
    return {
      message: 'The service ran into a problem. Try again in a moment.',
      retry: true,
    }
  }
  return { message: 'The workflow could not be started.', retry: false }
}
