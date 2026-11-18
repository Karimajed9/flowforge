import { beforeEach, expect, test, vi } from 'vitest'
import { clearCsrf } from '../auth/csrf.ts'
import { noteSignedOut } from '../auth/session.ts'
import { createWorkflow, getExecution, runWorkflow } from './client.ts'

beforeEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(noteSignedOut).mockClear()
  vi.mocked(clearCsrf).mockClear()
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const execution = {
  id: 21,
  workflowId: 4,
  status: 'SUCCEEDED',
  createdAt: '2026-11-16T15:04:00Z',
  failureReason: null,
}

const workflow = {
  id: 4,
  name: 'Owned',
  description: null,
  active: true,
  createdAt: '2026-11-16T15:04:00Z',
}

test('an API read sends the session cookie and no bearer token', async () => {
  const calls: Array<{ authorization: string | null; credentials: RequestCredentials | undefined }> = []
  vi.stubGlobal('fetch', (_input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      authorization: new Headers(init?.headers).get('Authorization'),
      credentials: init?.credentials,
    })
    return Promise.resolve(json(execution))
  })

  await getExecution(21)

  expect(calls).toEqual([{ authorization: null, credentials: 'include' }])
  expect(noteSignedOut).not.toHaveBeenCalled()
})

test('a state change sends the CSRF header and the session cookie', async () => {
  const calls: Array<{ url: string; header: string | null; credentials: RequestCredentials | undefined }> = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      header: new Headers(init?.headers).get('X-XSRF-TOKEN'),
      credentials: init?.credentials,
    })
    return Promise.resolve(json(workflow, 201))
  })

  await createWorkflow({ name: 'Owned', description: null })

  expect(calls).toEqual([
    {
      url: '/api/workflows',
      header: 'test-csrf',
      credentials: 'include',
    },
  ])
  expect(new Headers().get('Authorization')).toBeNull()
})

test('running a workflow sends the idempotency key and no bearer token', async () => {
  const calls: Array<{
    url: string
    key: string | null
    csrf: string | null
    authorization: string | null
    credentials: RequestCredentials | undefined
    body: string | undefined
  }> = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      key: new Headers(init?.headers).get('Idempotency-Key'),
      csrf: new Headers(init?.headers).get('X-XSRF-TOKEN'),
      authorization: new Headers(init?.headers).get('Authorization'),
      credentials: init?.credentials,
      body: typeof init?.body === 'string' ? init.body : undefined,
    })
    return Promise.resolve(
      json({
        id: 21,
        workflowId: 4,
        status: 'RECEIVED',
        createdAt: '2026-11-16T15:04:00Z',
        failureReason: null,
        payload: { secret: 'must-not-be-required' },
        webhookToken: 'must-not-store',
      }, 201),
    )
  })

  const execution = await runWorkflow(4, '{"amount":1}', 'manual-1')

  expect(execution).toMatchObject({ id: 21, status: 'RECEIVED' })
  expect(calls).toEqual([
    {
      url: '/api/workflows/4/executions',
      key: 'manual-1',
      csrf: 'test-csrf',
      authorization: null,
      credentials: 'include',
      body: '{"amount":1}',
    },
  ])
  expect(JSON.stringify(execution)).not.toContain('must-not-store')
})

test('a 401 returns to signed out without treating it as CSRF', async () => {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(json({ status: 401, error: 'Unauthorized' }, 401)),
  )

  await expect(getExecution(21)).rejects.toMatchObject({ status: 401 })
  expect(noteSignedOut).toHaveBeenCalledOnce()
  expect(clearCsrf).not.toHaveBeenCalled()
})

test('an invalid CSRF response stays signed in and drops the cached token', async () => {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(json({ status: 403, error: 'Forbidden' }, 403)),
  )

  await expect(createWorkflow({ name: 'Owned', description: null })).rejects.toMatchObject({
    status: 403,
  })
  expect(noteSignedOut).not.toHaveBeenCalled()
  expect(clearCsrf).toHaveBeenCalledOnce()
})
