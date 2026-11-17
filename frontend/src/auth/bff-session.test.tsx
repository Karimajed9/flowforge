// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { AppRoutes } from '../App.tsx'
import { clearCsrf } from './csrf.ts'

vi.unmock('../auth/session.ts')
vi.unmock('../auth/csrf.ts')

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
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

function profile(admin = false) {
  return {
    subject: admin ? 'admin' : 'user-a',
    issuer: 'http://auth.test/realms/flowforge',
    username: admin ? 'dev-admin' : 'Ada',
    permissions: admin ? ['flowforge.user', 'flowforge.admin'] : ['flowforge.user'],
  }
}

function emptyPage() {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    size: 20,
    number: 0,
    empty: true,
  }
}

function overview() {
  return {
    totalWorkflows: 0,
    activeWorkflows: 0,
    totalExecutions: 0,
    succeededExecutions: 0,
    failedExecutions: 0,
    successRate: null,
    windowDays: 7,
  }
}

function workflow() {
  return {
    id: 4,
    name: 'Owned',
    description: null,
    active: true,
    createdAt: '2026-11-16T15:04:00Z',
  }
}

type Call = {
  url: string
  method: string
  credentials: RequestCredentials | undefined
  authorization: string | null
  csrf: string | null
  body: string | null
}

function installFetch(handler: (call: Call) => Response | Promise<Response>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const call = {
      url: String(input),
      method: init?.method ?? 'GET',
      credentials: init?.credentials,
      authorization: new Headers(init?.headers).get('Authorization'),
      csrf: new Headers(init?.headers).get('X-XSRF-TOKEN'),
      body: typeof init?.body === 'string' ? init.body : null,
    }
    calls.push(call)
    return handler(call)
  })
  return calls
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

test('sign in navigates to the backend login endpoint', async () => {
  const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
  installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json({ status: 401, error: 'Unauthorized' }, 401)
    }
    return json({}, 404)
  })

  renderAt('/workflows')
  fireEvent.click(await screen.findByRole('button', { name: 'Sign in' }))

  expect(assign).toHaveBeenCalledWith('/api/auth/login')
  expect(screen.queryByText('New workflow')).toBeNull()
})

test('a profile signs the user in and a later mount still asks the server', async () => {
  const calls = installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile())
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    if (call.url.startsWith('/api/dashboard/overview')) {
      return json(overview())
    }
    return json(emptyPage())
  })

  const view = renderAt('/')
  expect(await screen.findByText('Ada')).toBeTruthy()
  expect(screen.queryByText('Administrator')).toBeNull()
  expect(await screen.findByText('Workflow activity')).toBeTruthy()
  expect(window.sessionStorage.getItem('access_token')).toBeNull()
  expect(window.localStorage.getItem('access_token')).toBeNull()

  view.unmount()
  renderAt('/')
  expect(await screen.findByText('Ada')).toBeTruthy()
  expect(calls.filter((call) => call.url === '/api/auth/me')).toHaveLength(2)
  expect(calls.every((call) => call.authorization === null)).toBe(true)
  expect(calls.every((call) => call.credentials === 'include')).toBe(true)
})

test('creating a workflow sends the cookie and the CSRF header', async () => {
  const calls = installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile())
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    if (call.url === '/api/workflows' && call.method === 'POST') {
      return json(workflow(), 201)
    }
    return json(emptyPage())
  })

  renderAt('/workflows')
  fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Owned' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))

  await waitFor(() => {
    expect(calls.some((call) => call.url === '/api/workflows' && call.method === 'POST')).toBe(true)
  })
  const create = calls.find((call) => call.url === '/api/workflows' && call.method === 'POST')
  expect(create?.credentials).toBe('include')
  expect(create?.authorization).toBeNull()
  expect(create?.csrf).toBe('csrf-1')
  expect(calls.some((call) => call.url === '/api/auth/csrf')).toBe(true)
})

test('a missing CSRF token does not send the workflow and keeps the user signed in', async () => {
  const calls = installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile())
    }
    if (call.url === '/api/auth/csrf') {
      return json({ status: 403, error: 'Forbidden' }, 403)
    }
    return json(emptyPage())
  })

  renderAt('/workflows')
  fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Owned' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))

  expect(await screen.findByRole('alert')).toBeTruthy()
  expect(screen.getByText('Ada')).toBeTruthy()
  expect(calls.some((call) => call.url === '/api/workflows' && call.method === 'POST')).toBe(false)
})

test('an invalid CSRF response stays on the signed-in page', async () => {
  let csrfReads = 0
  const calls = installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile())
    }
    if (call.url === '/api/auth/csrf') {
      csrfReads += 1
      return json({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: `csrf-${csrfReads}`,
      })
    }
    if (call.url === '/api/workflows' && call.method === 'POST') {
      return json({ status: 403, error: 'Forbidden' }, 403)
    }
    return json(emptyPage())
  })

  renderAt('/workflows')
  fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Owned' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))
  expect(await screen.findByRole('alert')).toBeTruthy()
  expect(screen.getByText('Ada')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))
  await waitFor(() => {
    expect(calls.filter((call) => call.url === '/api/auth/csrf').length).toBeGreaterThan(1)
  })
})

test('a 401 leaves the user signed out and does not navigate to login', async () => {
  const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
  let expired = false
  installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(expired ? { status: 401, error: 'Unauthorized' } : profile(), expired ? 401 : 200)
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    if (call.url.startsWith('/api/workflows')) {
      expired = true
      return json({ status: 401, error: 'Unauthorized' }, 401)
    }
    return json(emptyPage())
  })

  renderAt('/workflows')
  expect(await screen.findByText('Ada')).toBeTruthy()
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
  expect(assign).not.toHaveBeenCalled()
  expect(window.location.pathname).not.toContain('keycloak')
})

test('sign out posts to the backend logout endpoint with the CSRF field', async () => {
  const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
  installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile())
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    return json(overview())
  })

  renderAt('/')
  fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

  await waitFor(() => {
    expect(submit).toHaveBeenCalled()
  })
  const form = document.querySelector('form[action="/api/auth/logout"]')
  expect(form).toBeTruthy()
  expect(form?.getAttribute('method')?.toUpperCase()).toBe('POST')
  const field = form?.querySelector('input[name="_csrf"]')
  expect(field?.getAttribute('value')).toBe('csrf-1')
})

test('a signed-out session does not open a protected workflow page', async () => {
  installFetch(() => json({ status: 401, error: 'Unauthorized' }, 401))

  renderAt('/workflows')

  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
  expect(screen.queryByText('New workflow')).toBeNull()
})

test('an administrator is labeled and a user is not', async () => {
  installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile(true))
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    return json(emptyPage())
  })

  const view = renderAt('/workflows')
  expect(await screen.findByText('Administrator')).toBeTruthy()
  expect(screen.getByText('dev-admin')).toBeTruthy()

  view.unmount()
  installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile(false))
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    return json(emptyPage())
  })
  renderAt('/workflows')
  expect(await screen.findByText('Ada')).toBeTruthy()
  expect(screen.queryByText('Administrator')).toBeNull()
})

test('startup removes legacy oidc token keys and leaves other storage', async () => {
  window.sessionStorage.setItem(
    'oidc.user:http://localhost:8180/realms/flowforge:flowforge-api',
    '{"access_token":"legacy-access","refresh_token":"legacy-refresh"}',
  )
  window.localStorage.setItem(
    'oidc.state-1',
    '{"code_verifier":"legacy-verifier","id_token":"legacy-id"}',
  )
  window.localStorage.setItem('theme', 'navy')
  window.localStorage.setItem('oidc.note', 'keep-me')

  installFetch((call) => {
    if (call.url === '/api/auth/me') {
      return json(profile())
    }
    if (call.url === '/api/auth/csrf') {
      return json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' })
    }
    return json(emptyPage())
  })

  renderAt('/workflows')
  expect(await screen.findByText('Ada')).toBeTruthy()
  expect(window.sessionStorage.length).toBe(0)
  expect(window.localStorage.getItem('oidc.state-1')).toBeNull()
  expect(window.localStorage.getItem('theme')).toBe('navy')
  expect(window.localStorage.getItem('oidc.note')).toBe('keep-me')
  expect(JSON.stringify(window.localStorage)).not.toContain('legacy-access')
  expect(JSON.stringify(window.sessionStorage)).not.toContain('legacy-verifier')
})
