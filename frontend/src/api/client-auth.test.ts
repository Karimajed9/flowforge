import { beforeEach, expect, test, vi } from 'vitest'

vi.mock('../auth/session.ts', () => ({
  getAccessToken: vi.fn(),
  clearSession: vi.fn(),
}))

import { clearSession, getAccessToken } from '../auth/session.ts'
import { getExecution } from './client.ts'

beforeEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(getAccessToken).mockReset()
  vi.mocked(clearSession).mockReset()
  vi.mocked(clearSession).mockResolvedValue(undefined)
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

test('an API request sends the access token', async () => {
  vi.mocked(getAccessToken).mockResolvedValue('header-token')
  const calls: string[] = []
  vi.stubGlobal('fetch', (_input: RequestInfo | URL, init?: RequestInit) => {
    calls.push(new Headers(init?.headers).get('Authorization') ?? '')
    return Promise.resolve(json(execution))
  })

  await getExecution(21)

  expect(calls).toEqual(['Bearer header-token'])
  expect(clearSession).not.toHaveBeenCalled()
})

test('a 401 clears the local session', async () => {
  vi.mocked(getAccessToken).mockResolvedValue('header-token')
  vi.stubGlobal('fetch', () =>
    Promise.resolve(json({ detail: 'Full authentication is required' }, 401)),
  )

  await expect(getExecution(21)).rejects.toMatchObject({
    name: 'ApiError',
    status: 401,
  })
  expect(clearSession).toHaveBeenCalledOnce()
})
