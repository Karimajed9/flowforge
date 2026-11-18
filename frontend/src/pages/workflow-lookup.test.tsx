// @vitest-environment happy-dom
import { cleanup, fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { renderApp } from '../test/renderApp.tsx'
import { clearCsrf } from '../auth/csrf.ts'

vi.unmock('../auth/session.ts')
vi.unmock('../auth/csrf.ts')

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.sessionStorage.clear()
  window.localStorage.clear()
})

beforeEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  clearCsrf()
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function profile() {
  return {
    subject: 'user',
    issuer: 'http://auth.test/realms/flowforge',
    username: 'Ada',
    permissions: ['flowforge.user'],
  }
}

function csrf() {
  return { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' }
}

function workflow() {
  return {
    id: 4,
    name: 'Onboarding',
    description: 'New hire',
    active: true,
    createdAt: '2026-11-16T15:00:00Z',
  }
}

function execution() {
  return {
    id: 21,
    workflowId: 4,
    status: 'SUCCEEDED',
    createdAt: '2026-11-16T15:04:00Z',
    failureReason: null,
  }
}

function installFetch(handler: (url: string) => Response | Promise<Response>) {
  const calls: string[] = []
  let signedIn = true
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    if (url.includes('/api/auth/me')) {
      return Promise.resolve(
        signedIn ? json(profile()) : json({ status: 401, error: 'Unauthorized' }, 401),
      )
    }
    if (url.includes('/api/auth/csrf')) {
      return Promise.resolve(json(csrf()))
    }
    const response = handler(url)
    return Promise.resolve(response).then((resolved) => {
      if (resolved.status === 401) {
        signedIn = false
      }
      return resolved
    })
  })
  return calls
}

function renderAt(path: string) {
  return renderApp(path)
}

test('a workflow loads from the detail route', async () => {
  installFetch((url) => {
    if (url === '/api/workflows/4') {
      return json(workflow())
    }
    return json([])
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.getByText('New hire')).toBeTruthy()
})

test('an unknown workflow id shows the friendly not-found state', async () => {
  const calls = installFetch(() => json({ detail: 'Workflow not found' }, 404))

  renderAt('/workflows/0')
  expect(await screen.findByRole('heading', { name: 'Workflow not found' })).toBeTruthy()
  expect(
    screen.getByText("This workflow doesn't exist, or you don't have access to it."),
  ).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Back to Workflows' }).getAttribute('href')).toBe(
    '/workflows',
  )
  expect(calls.some((url) => url.includes('/api/workflows/'))).toBe(false)
  expect(screen.queryByText('Workflow id must be a positive number.')).toBeNull()
})

test('a missing workflow does not say whether it exists', async () => {
  installFetch(() => json({ detail: 'Workflow not found' }, 404))

  renderAt('/workflows/99')
  expect(await screen.findByRole('heading', { name: 'Workflow not found' })).toBeTruthy()
  expect(
    screen.getByText("This workflow doesn't exist, or you don't have access to it."),
  ).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Back to Workflows' })).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
})

test('a workflow 401 returns to the sign-in panel', async () => {
  installFetch(() => json({ status: 401, error: 'Unauthorized' }, 401))

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeTruthy()
  expect(screen.queryByRole('heading', { name: 'Workflow not found' })).toBeNull()
  expect(screen.queryByRole('heading', { name: 'Access denied' })).toBeNull()
})

test('a workflow 403 shows access denied', async () => {
  installFetch(() => json({ status: 403, error: 'Forbidden' }, 403))

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
  expect(screen.getByText("You don't have access to this workflow.")).toBeTruthy()
  expect(screen.queryByRole('heading', { name: 'Workflow not found' })).toBeNull()
  expect(screen.queryByRole('heading', { name: 'Sign in' })).toBeNull()
})

test('a workflow 500 offers retry', async () => {
  let attempts = 0
  installFetch((url) => {
    if (url === '/api/workflows/4' && attempts === 0) {
      attempts += 1
      return json({ status: 500, error: 'Internal Server Error' }, 500)
    }
    if (url === '/api/workflows/4') {
      return json(workflow())
    }
    return json([])
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('The service ran into a problem. Try again in a moment.')).toBeTruthy()
  expect(screen.queryByText('Internal Server Error')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
})

test('an executions lookup does not show the raw 404', async () => {
  installFetch(() => json({ status: 404, error: 'Not Found' }, 404))

  renderAt('/executions?workflowId=99')

  expect(await screen.findByRole('heading', { name: 'Workflow not found' })).toBeTruthy()
  expect(
    screen.getByText("This workflow doesn't exist, or you don't have access to it."),
  ).toBeTruthy()
  const back = screen.getByRole('link', { name: 'Back to Workflows' })
  expect(back.getAttribute('href')).toBe('/workflows')
  expect(screen.queryByText('Not Found')).toBeNull()
  expect(screen.queryByText('Request failed (404)')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull()

  fireEvent.click(back)
  expect(await screen.findByLabelText('Search')).toBeTruthy()
})

test('an executions lookup 401 stays on the sign-in panel', async () => {
  installFetch(() => json({ status: 401, error: 'Unauthorized' }, 401))

  renderAt('/executions?workflowId=4')

  expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeTruthy()
  expect(screen.queryByText('Request failed (401)')).toBeNull()
  expect(screen.queryByRole('heading', { name: 'Workflow not found' })).toBeNull()
})

test('an executions lookup 403 is access denied', async () => {
  installFetch(() => json({ status: 403, error: 'Forbidden' }, 403))

  renderAt('/executions?workflowId=4')

  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
  expect(screen.getByText("You don't have access to this workflow.")).toBeTruthy()
  expect(screen.queryByText('Forbidden')).toBeNull()
  expect(screen.queryByText('Request failed (403)')).toBeNull()
})

test('an executions lookup 500 keeps try again', async () => {
  let failed = true
  installFetch((url) => {
    if (url.includes('/api/workflows/4') && failed) {
      return json({ status: 500, error: 'Internal Server Error' }, 500)
    }
    if (url === '/api/workflows/4') {
      return json({
        id: 4,
        name: 'Onboarding',
        description: null,
        active: true,
        createdAt: '2026-11-16T15:00:00Z',
      })
    }
    return json({
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: 20,
      number: 0,
      empty: true,
    })
  })

  renderAt('/executions?workflowId=4')
  expect(await screen.findByRole('button', { name: 'Try again' })).toBeTruthy()
  expect(screen.queryByText('Internal Server Error')).toBeNull()
  expect(screen.queryByText('Request failed (500)')).toBeNull()
  failed = false
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(
    await screen.findByRole('heading', { name: 'No executions for this workflow' }),
  ).toBeTruthy()
})

test('a missing execution offers a way back', async () => {
  installFetch(() => json({ detail: 'Execution not found' }, 404))

  renderAt('/executions/nope')
  expect(await screen.findByRole('heading', { name: 'Execution not found' })).toBeTruthy()
  expect(
    screen.getByText("This execution doesn't exist, or you don't have access to it."),
  ).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Back to Executions' }).getAttribute('href')).toBe(
    '/executions',
  )
})

test('an execution 401 returns to the sign-in panel', async () => {
  installFetch(() => json({ status: 401, error: 'Unauthorized' }, 401))

  renderAt('/executions/21')
  expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeTruthy()
  expect(screen.queryByRole('heading', { name: 'Execution not found' })).toBeNull()
})

test('an execution 403 shows access denied', async () => {
  installFetch(() => json({ status: 403, error: 'Forbidden' }, 403))

  renderAt('/executions/21')
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
  expect(screen.getByText("You don't have access to this execution.")).toBeTruthy()
  expect(screen.queryByText('Forbidden')).toBeNull()
  expect(screen.queryByText('Request failed (403)')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
})

test('an execution 500 offers retry', async () => {
  let failed = false
  installFetch((url) => {
    if (url.startsWith('/api/executions/21') && !failed) {
      failed = true
      return json({ status: 500, error: 'Internal Server Error' }, 500)
    }
    if (url === '/api/executions/21') {
      return json(execution())
    }
    return json([])
  })

  renderAt('/executions/21')
  expect(await screen.findByRole('button', { name: 'Try again' })).toBeTruthy()
  expect(screen.getByText('The service ran into a problem. Try again in a moment.')).toBeTruthy()
  expect(screen.queryByText('Internal Server Error')).toBeNull()
  expect(screen.queryByText('Request failed (500)')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('heading', { name: 'Execution 21' })).toBeTruthy()
})
