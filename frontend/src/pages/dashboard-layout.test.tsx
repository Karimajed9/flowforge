// @vitest-environment happy-dom
import { cleanup, fireEvent, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { renderApp } from '../test/renderApp.tsx'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
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

function workflowPage(content: unknown[], number: number) {
  return {
    content,
    totalElements: 2,
    totalPages: 2,
    size: 20,
    number,
    empty: content.length === 0,
  }
}

function renderAt(path: string) {
  return renderApp(path)
}

test('pagination and opening a workflow keep the shell mounted', async () => {
  let releasePage: ((response: Response) => void) | null = null
  installFetch((url) => {
    const path = new URL(url, 'http://localhost')
    if (path.pathname === '/api/workflows' && path.searchParams.get('page') === '1') {
      return new Promise<Response>((resolve) => {
        releasePage = resolve
      })
    }
    if (path.pathname === '/api/workflows') {
      return json(workflowPage([workflow(4, 'Alpha')], 0))
    }
    if (path.pathname === '/api/workflows/8' || path.pathname === '/api/workflows/8/steps') {
      return new Promise<Response>(() => {})
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows')
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()

  const header = screen.getByTestId('app-header')
  const sidebar = screen.getByTestId('app-sidebar')
  const content = screen.getByTestId('app-content')
  const main = screen.getByTestId('app-main')
  const search = screen.getByTestId('workflow-search-bar')
  const filters = screen.getByRole('group', { name: 'Status' })
  const results = screen.getByTestId('workflow-results')

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)
  expect(screen.getByTestId('app-content')).toBe(content)
  expect(screen.getByTestId('app-main')).toBe(main)
  expect(header.isConnected).toBe(true)
  expect(sidebar.isConnected).toBe(true)
  expect(main.isConnected).toBe(true)
  expect(screen.getByTestId('workflow-search-bar')).toBe(search)
  expect(screen.getByRole('group', { name: 'Status' })).toBe(filters)
  expect(screen.getByTestId('workflow-results')).toBe(results)
  expect(main.contains(search)).toBe(true)
  expect(main.contains(filters)).toBe(true)
  expect(results.contains(screen.getByRole('link', { name: 'Alpha' }))).toBe(true)
  expect(results.getAttribute('aria-busy')).toBe('true')
  expect(results.className).toContain('min-h-[max(14rem,calc(100svh-12rem))]')
  expect(results.className).toContain('[overflow-anchor:none]')
  expect(main.className).toContain('min-h-[calc(100svh-4.75rem)]')
  expect(content.className).toContain('h-full')
  expect(content.className).toContain('overflow-y-auto')

  const resolvePage = releasePage as ((response: Response) => void) | null
  if (!resolvePage) {
    throw new Error('The next page did not start a request')
  }
  resolvePage(json(workflowPage([workflow(8, 'Beta')], 1)))

  expect(await screen.findByRole('link', { name: 'Beta' })).toBeTruthy()
  expect(screen.getByTestId('workflow-search-bar')).toBe(search)
  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)

  fireEvent.click(screen.getByRole('link', { name: 'Open workflow' }))

  expect(await screen.findByText('Loading workflow')).toBeTruthy()
  const detail = screen.getByTestId('workflow-detail')
  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)
  expect(screen.getByTestId('app-content')).toBe(content)
  expect(screen.getByTestId('app-main')).toBe(main)
  expect(header.isConnected).toBe(true)
  expect(sidebar.isConnected).toBe(true)
  expect(main.contains(detail)).toBe(true)
  expect(detail.contains(screen.getByText('Loading workflow'))).toBe(true)
  expect(detail.className).toContain('min-h-[max(14rem,calc(100svh-12rem))]')
  expect(detail.className).toContain('[overflow-anchor:none]')
  expect(search.isConnected).toBe(false)
})

test('execution pagination and opening an execution keep the shell mounted', async () => {
  let releasePage: ((response: Response) => void) | null = null
  installFetch((url) => {
    const path = new URL(url, 'http://localhost')
    if (path.pathname === '/api/workflows/4/executions' && path.searchParams.get('page') === '1') {
      return new Promise<Response>((resolve) => {
        releasePage = resolve
      })
    }
    if (path.pathname === '/api/workflows/4') {
      return json({
        id: 4,
        name: 'Onboarding',
        description: null,
        active: true,
        createdAt: '2026-11-16T15:00:00Z',
      })
    }
    if (path.pathname === '/api/workflows/4/executions') {
      return json(executionPage(21, 0))
    }
    if (
      path.pathname === '/api/executions/22' ||
      path.pathname === '/api/executions/22/steps'
    ) {
      return new Promise<Response>(() => {})
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/executions?workflowId=4')
  expect(await screen.findByRole('heading', { name: 'Execution 21' })).toBeTruthy()
  const header = screen.getByTestId('app-header')
  const sidebar = screen.getByTestId('app-sidebar')
  const content = screen.getByTestId('app-content')
  const main = screen.getByTestId('app-main')
  const workflowId = screen.getByLabelText('Workflow id')
  const results = screen.getByTestId('execution-results')

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)
  expect(screen.getByTestId('app-content')).toBe(content)
  expect(screen.getByTestId('app-main')).toBe(main)
  expect(screen.getByLabelText('Workflow id')).toBe(workflowId)
  expect(screen.getByTestId('execution-results')).toBe(results)
  expect(results.contains(screen.getByRole('heading', { name: 'Execution 21' }))).toBe(true)
  expect(results.getAttribute('aria-busy')).toBe('true')
  expect(results.className).toContain('min-h-[max(14rem,calc(100svh-12rem))]')
  expect(main.className).toContain('min-h-[calc(100svh-4.75rem)]')

  const resolvePage = releasePage as ((response: Response) => void) | null
  if (!resolvePage) {
    throw new Error('The next execution page did not start a request')
  }
  resolvePage(json(executionPage(22, 1)))

  expect(await screen.findByRole('heading', { name: 'Execution 22' })).toBeTruthy()
  expect(screen.getByLabelText('Workflow id')).toBe(workflowId)
  fireEvent.click(screen.getByRole('link', { name: 'Open details' }))

  expect(await screen.findByText('Loading execution')).toBeTruthy()
  const detail = screen.getByTestId('execution-detail')
  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)
  expect(screen.getByTestId('app-content')).toBe(content)
  expect(screen.getByTestId('app-main')).toBe(main)
  expect(header.isConnected).toBe(true)
  expect(sidebar.isConnected).toBe(true)
  expect(main.contains(detail)).toBe(true)
  expect(detail.contains(screen.getByText('Loading execution'))).toBe(true)
  expect(detail.className).toContain('min-h-[max(14rem,calc(100svh-12rem))]')
  expect(detail.className).toContain('[overflow-anchor:none]')
  expect(workflowId.isConnected).toBe(false)
})

function executionPage(id: number, number: number) {
  return {
    content: [
      {
        id,
        workflowId: 4,
        status: 'SUCCEEDED',
        createdAt: '2026-11-16T15:04:00Z',
        failureReason: null,
      },
    ],
    totalElements: 2,
    totalPages: 2,
    size: 20,
    number,
    empty: false,
  }
}

function installFetch(
  handler: (url: string) => Response | Promise<Response>,
) {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => handler(String(input)))
}
