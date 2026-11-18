import { afterEach, expect, test, vi } from 'vitest'
import {
  ApiError,
  getExecution,
  getExecutionSteps,
  listWorkflowExecutions,
  updateWorkflowStep,
} from './client.ts'

afterEach(() => {
  vi.unstubAllGlobals()
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

test('execution GET uses a relative URL', async () => {
  const calls: Array<{ url: string; method: string | undefined }> = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), method: init?.method })
    return Promise.resolve(
      json({
        id: 21,
        workflowId: 4,
        status: 'SUCCEEDED',
        createdAt: '2026-11-16T15:04:00Z',
        failureReason: null,
      }),
    )
  })

  const execution = await getExecution(21)

  expect(calls).toEqual([{ url: '/api/executions/21', method: 'GET' }])
  expect(execution.failureReason).toBeNull()
})

test('HTTP 404 keeps the status and problem detail', async () => {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(json({ detail: 'Execution not found' }, 404)),
  )

  await expect(getExecution(9)).rejects.toMatchObject({
    name: 'ApiError',
    status: 404,
    message: 'Execution not found',
  })
})

test('network failures become an API error', async () => {
  vi.stubGlobal('fetch', () => Promise.reject(new TypeError('Failed to fetch')))

  await expect(getExecution(21)).rejects.toEqual(
    expect.objectContaining({
      status: 0,
      message: 'The API could not be reached',
    }),
  )
  await expect(getExecution(21)).rejects.toBeInstanceOf(ApiError)
})

test('invalid JSON is reported without a raw parser exception', async () => {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(new Response('not-json', { status: 200 })),
  )

  const error = await getExecution(21).then(
    () => null,
    (caught: unknown) => caught,
  )

  expect(error).toBeInstanceOf(ApiError)
  expect(error).toMatchObject({
    status: 200,
    message: 'The API returned invalid JSON',
  })
  expect(error).not.toBeInstanceOf(SyntaxError)
})

test('a cancelled request rejects with AbortError', async () => {
  const controller = new AbortController()
  vi.stubGlobal('fetch', (_input: RequestInfo | URL, init?: RequestInit) => {
    return new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('Aborted', 'AbortError'))
      })
    })
  })

  const pending = getExecution(21, controller.signal)
  controller.abort()

  await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
})

test('NOT_STARTED steps keep null attempt and timestamps', async () => {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(
      json([
        {
          stepId: 8,
          name: 'Call API',
          stepType: 'HTTP_REQUEST',
          position: 2,
          status: 'NOT_STARTED',
          attemptNumber: null,
          failureReason: null,
          startedAt: null,
          updatedAt: null,
          leaseUntil: null,
        },
        {
          stepId: 3,
          name: 'Check fields',
          stepType: 'VALIDATE_FIELDS',
          position: 1,
          status: 'FAILED',
          attemptNumber: 1,
          failureReason: 'HTTP status 500',
          startedAt: '2026-11-16T15:04:01Z',
          updatedAt: '2026-11-16T15:04:02Z',
          leaseUntil: '2026-11-16T15:05:00Z',
        },
      ]),
    ),
  )

  const steps = await getExecutionSteps(21)

  expect(steps.map((step) => step.name)).toEqual(['Check fields', 'Call API'])
  expect(steps[1]).toMatchObject({
    status: 'NOT_STARTED',
    attemptNumber: null,
    startedAt: null,
    updatedAt: null,
    leaseUntil: null,
  })
  expect(steps[0].failureReason).toBe('HTTP status 500')
})

test('workflow execution pages request page and size', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost')
    expect(url.pathname).toBe('/api/workflows/4/executions')
    expect(url.searchParams.get('page')).toBe('1')
    expect(url.searchParams.get('size')).toBe('20')
    return Promise.resolve(
      json({
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 20,
        number: 1,
        empty: true,
      }),
    )
  })

  const page = await listWorkflowExecutions(4, 1)

  expect(page.content).toEqual([])
  expect(page.number).toBe(1)
})

test('a step change without a revision is rejected', async () => {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(
      json({
        step: {
          id: 3,
          name: 'Check fields',
          stepType: 'VALIDATE_FIELDS',
          position: 1,
          configuration: { requiredFields: ['email'] },
        },
      }),
    ),
  )

  await expect(
    updateWorkflowStep(4, 3, {
      name: 'Check fields',
      configuration: { requiredFields: ['email'] },
      revision: 1,
    }),
  ).rejects.toMatchObject({
    name: 'ApiError',
    status: 0,
    message: 'The API returned an unexpected response',
  })
})
