// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { AppRoutes } from '../App.tsx'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

type Call = { url: string; method: string }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

function overview(overrides: Record<string, unknown> = {}) {
  return {
    totalWorkflows: 2,
    activeWorkflows: 1,
    totalExecutions: 5,
    succeededExecutions: 1,
    failedExecutions: 1,
    successRate: 0.5,
    windowDays: 7,
    ...overrides,
  }
}

function executionPage(content: unknown[], page = 0, totalPages = 1) {
  return {
    content,
    totalElements: totalPages === 0 ? 0 : 21,
    totalPages,
    size: 20,
    number: page,
    empty: content.length === 0,
  }
}

function recentExecution(overrides: Record<string, unknown> = {}) {
  return {
    id: 21,
    workflowId: 4,
    workflowName: 'Onboarding',
    status: 'FAILED',
    createdAt: '2026-11-16T15:04:00Z',
    failureReason: 'downstream rejected the request',
    payload: { token: 'payload-secret-value' },
    idempotencyKey: 'idem-secret-value',
    webhookToken: 'webhook-token-secret',
    ...overrides,
  }
}

function workflowPage(content: unknown[], page = 0, totalPages = 1, total = content.length) {
  return {
    content,
    totalElements: total,
    totalPages,
    size: 20,
    number: page,
    empty: content.length === 0,
  }
}

function workflow(id: number, name: string) {
  return {
    id,
    name,
    description: null,
    active: true,
    createdAt: '2026-11-16T15:00:00Z',
  }
}

function installFetch(
  handler: (call: Call) => Response | Promise<Response>,
) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), method: init?.method ?? 'GET' }
    calls.push(call)
    return handler(call)
  })
  return calls
}

function pathOf(url: string) {
  return new URL(url, 'http://localhost').pathname
}

test('overview shows aggregate counts and a success rate', async () => {
  installFetch((call) => {
    if (pathOf(call.url) === '/api/dashboard/overview') {
      return json(overview())
    }
    return json(executionPage([recentExecution()]))
  })

  renderAt('/')

  expect(await screen.findByText('50%')).toBeTruthy()
  expect(screen.getByText('Total workflows').parentElement?.textContent).toContain('2')
  expect(screen.getByText('Active workflows').parentElement?.textContent).toContain('1')
  expect(screen.getByText('Total executions').parentElement?.textContent).toContain('5')
  expect(screen.getByText('Succeeded').parentElement?.textContent).toContain('1')
  expect(screen.getByText('Failed').parentElement?.textContent).toContain('1')
  expect(screen.getAllByText('Last 7 days').length).toBeGreaterThan(0)
  expect(screen.getByRole('link', { name: 'Onboarding' }).getAttribute('href')).toBe(
    '/workflows/4',
  )
  expect(screen.getByText('FAILED').className).toContain('red')
  expect(screen.getByText('downstream rejected the request')).toBeTruthy()
  expect(document.body.textContent).not.toContain('payload-secret-value')
  expect(document.body.textContent).not.toContain('idem-secret-value')
  expect(document.body.textContent).not.toContain('webhook-token-secret')
})

test('a window with no finished executions shows an empty rate', async () => {
  installFetch((call) => {
    if (pathOf(call.url) === '/api/dashboard/overview') {
      return json(
        overview({
          totalWorkflows: 1,
          activeWorkflows: 1,
          totalExecutions: 2,
          succeededExecutions: 0,
          failedExecutions: 0,
          successRate: null,
        }),
      )
    }
    return json(executionPage([], 0, 0))
  })

  renderAt('/')

  expect(
    await screen.findByText('No finished executions in the last 7 days'),
  ).toBeTruthy()
  expect(screen.getByText('Success rate').parentElement?.textContent).toContain('—')
  expect(document.body.textContent).not.toContain('NaN')
  expect(document.body.textContent).not.toContain('Infinity')
  expect(await screen.findByRole('heading', { name: 'No executions yet' })).toBeTruthy()
})

test('overview shows loading and error states and can refresh', async () => {
  const pending: Array<(response: Response) => void> = []
  const calls = installFetch(
    () =>
      new Promise<Response>((resolve) => {
        pending.push(resolve)
      }),
  )

  renderAt('/')
  expect(await screen.findByText('Loading overview')).toBeTruthy()
  expect(screen.getByText('Loading recent executions')).toBeTruthy()

  for (const resolve of pending) {
    resolve(json({ detail: 'Database unavailable' }, 500))
  }
  expect(await screen.findAllByText('Database unavailable')).toHaveLength(2)
  expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(2)
  expect(calls.length).toBeGreaterThan(0)
})

test('refresh reloads the overview and recent executions', async () => {
  let overviewLoads = 0
  let recentLoads = 0
  installFetch((call) => {
    if (pathOf(call.url) === '/api/dashboard/overview') {
      overviewLoads += 1
      return json(overview({ totalWorkflows: overviewLoads }))
    }
    recentLoads += 1
    return json(executionPage([recentExecution({ id: 20 + recentLoads })]))
  })

  renderAt('/')
  expect(await screen.findByText('50%')).toBeTruthy()
  expect(screen.getByText('Total workflows').parentElement?.textContent).toContain('1')

  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
  expect(await screen.findByText('Loading overview')).toBeTruthy()
  expect(await screen.findByText('50%')).toBeTruthy()
  expect(screen.getByText('Total workflows').parentElement?.textContent).toContain('2')
  expect(overviewLoads).toBe(2)
  expect(recentLoads).toBe(2)
  expect(screen.getByText(/Execution 22/)).toBeTruthy()
})

test('recent executions paginate', async () => {
  const calls = installFetch((call) => {
    const page = new URL(call.url, 'http://localhost').searchParams.get('page')
    if (pathOf(call.url) === '/api/dashboard/overview') {
      return json(overview())
    }
    return json(
      executionPage(
        [recentExecution({ id: page === '1' ? 22 : 21, workflowName: page === '1' ? 'Billing' : 'Onboarding' })],
        page === '1' ? 1 : 0,
        2,
      ),
    )
  })

  renderAt('/')
  expect(await screen.findByRole('link', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.getByText('Page 1 of 2')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))
  expect(await screen.findByRole('link', { name: 'Billing' })).toBeTruthy()
  expect(screen.getByText('Page 2 of 2')).toBeTruthy()
  expect(calls.some((call) => call.url.includes('/api/executions?page=1&size=20'))).toBe(
    true,
  )

  fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
  expect(await screen.findByRole('link', { name: 'Onboarding' })).toBeTruthy()
})

test('workflow list paginates, refreshes, and shows an error', async () => {
  const calls = installFetch((call) => {
    const page = new URL(call.url, 'http://localhost').searchParams.get('page')
    if (pathOf(call.url) !== '/api/workflows') {
      return json({ detail: 'Not found' }, 404)
    }
    if (page === '1') {
      return json(
        workflowPage([workflow(8, 'Billing')], 1, 2, 21),
      )
    }
    return json(workflowPage([workflow(4, 'Onboarding')], 0, 2, 21))
  })

  renderAt('/workflows')
  expect(await screen.findByRole('link', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.getByText('Page 1 of 2')).toBeTruthy()
  expect(calls.some((call) => call.url.includes('/api/workflows?page=0&size=20'))).toBe(
    true,
  )

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))
  expect(await screen.findByRole('link', { name: 'Billing' })).toBeTruthy()
  expect(screen.getByText('Page 2 of 2')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
  expect(await screen.findByText('Loading workflows')).toBeTruthy()
  expect(await screen.findByRole('link', { name: 'Billing' })).toBeTruthy()
})

test('workflow list shows a load error', async () => {
  installFetch(() => Promise.reject(new TypeError('Failed to fetch')))

  renderAt('/workflows')
  expect(
    await screen.findByRole('heading', { name: 'Something went wrong' }),
  ).toBeTruthy()
  expect(screen.getByText('The API could not be reached')).toBeTruthy()
})
