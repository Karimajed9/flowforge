// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { MemoryRouter, useLocation } from 'react-router'
import { AppRoutes } from '../App.tsx'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

type Call = { url: string; method: string }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function workflow(id: number, name: string, active = false) {
  return {
    id,
    name,
    description: null,
    active,
    createdAt: '2026-11-16T15:00:00Z',
  }
}

function page(
  content: unknown[],
  total = content.length,
  number = 0,
) {
  return {
    content,
    totalElements: total,
    totalPages: total === 0 ? 0 : 1,
    size: 20,
    number,
    empty: content.length === 0,
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

function listCalls(calls: Call[]) {
  return calls.filter((call) => {
    const path = new URL(call.url, 'http://localhost')
    return call.method === 'GET' && path.pathname === '/api/workflows'
  })
}

function params(url: string) {
  return new URL(url, 'http://localhost').searchParams
}

function RouteLocation() {
  const location = useLocation()
  return <p data-testid="route-location">{`${location.pathname}${location.search}`}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
      <RouteLocation />
    </MemoryRouter>,
  )
}

test('the workflows address restores search, status, and page', async () => {
  const calls = installFetch((call) => {
    const query = params(call.url)
    if (query.get('q') === 'Alpha' && query.get('active') === 'true') {
      return json(page([workflow(4, 'Alpha', true)], 21, 1))
    }
    return json(page([]))
  })

  renderAt('/workflows?q=Alpha&status=active&page=1')

  const search = await screen.findByLabelText('Search')
  expect((search as HTMLInputElement).value).toBe('Alpha')
  expect(screen.getByRole('button', { name: 'Active' }).getAttribute('aria-pressed')).toBe(
    'true',
  )
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()
  const listed = listCalls(calls).at(-1)
  expect(listed).toBeTruthy()
  expect(params(listed?.url ?? '').get('q')).toBe('Alpha')
  expect(params(listed?.url ?? '').get('active')).toBe('true')
  expect(params(listed?.url ?? '').get('page')).toBe('1')
})

test('search text is debounced and resets the page', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true })
  let release: ((response: Response) => void) | null = null
  const calls = installFetch((call) => {
    const query = params(call.url).get('q')
    if (query === 'alpha') {
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    return json(page([workflow(4, 'Alpha'), workflow(8, 'Beta')]))
  })

  renderAt('/workflows?page=2')
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()
  const results = screen.getByTestId('workflow-results')
  const before = listCalls(calls).length

  fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'al' } })
  fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'alpha' } })
  await vi.advanceTimersByTimeAsync(299)
  expect(listCalls(calls)).toHaveLength(before)

  await vi.advanceTimersByTimeAsync(1)
  await waitFor(() => {
    expect(params(listCalls(calls).at(-1)?.url ?? '').get('q')).toBe('alpha')
  })
  expect(screen.getByRole('link', { name: 'Alpha' })).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Beta' })).toBeTruthy()
  expect(screen.getByTestId('workflow-results')).toBe(results)
  expect(results.className).toContain('min-h-[max(14rem,calc(100svh-12rem))]')
  expect(results.querySelector('.py-14')).toBeNull()
  const listed = listCalls(calls).at(-1)
  expect(params(listed?.url ?? '').get('page')).toBe('0')
  expect(params(listed?.url ?? '').get('active')).toBeNull()

  const resolve = release as ((response: Response) => void) | null
  if (!resolve) {
    throw new Error('The search did not request a new page')
  }
  resolve(json(page([workflow(4, 'Alpha')])))
  await waitFor(() => {
    expect(screen.queryByRole('link', { name: 'Beta' })).toBeNull()
  })
  expect(screen.getByRole('link', { name: 'Alpha' })).toBeTruthy()
  expect(screen.getByTestId('workflow-results')).toBe(results)
})

test('status filters are sent to the server and clear the page', async () => {
  const calls = installFetch((call) => {
    const active = params(call.url).get('active')
    if (active === 'false') {
      return json(page([workflow(8, 'Beta', false)]))
    }
    return json(page([workflow(4, 'Alpha', true)]))
  })

  renderAt('/workflows?page=3')
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Inactive' }))

  expect(await screen.findByRole('link', { name: 'Beta' })).toBeTruthy()
  const listed = listCalls(calls).at(-1)
  expect(params(listed?.url ?? '').get('active')).toBe('false')
  expect(params(listed?.url ?? '').get('page')).toBe('0')
  expect(params(listed?.url ?? '').get('q')).toBeNull()
})

test('an empty search result is distinct from an empty library', async () => {
  installFetch((call) => {
    if (params(call.url).get('q') === 'missing') {
      return json(page([]))
    }
    return json(page([]))
  })

  renderAt('/workflows?q=missing')
  expect(await screen.findByRole('heading', { name: 'No matching workflows' })).toBeTruthy()
  expect(screen.queryByRole('heading', { name: 'No workflows yet' })).toBeNull()
})

test('changing status keeps the search bar and results region in place', async () => {
  let release: ((response: Response) => void) | null = null
  const calls = installFetch((call) => {
    if (params(call.url).get('active') === 'false') {
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    return json(page([workflow(4, 'Alpha', true), workflow(8, 'Beta', true)]))
  })

  renderAt('/workflows')
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()
  const header = screen.getByTestId('app-header')
  const sidebar = screen.getByTestId('app-sidebar')
  const main = screen.getByTestId('app-main')
  const search = screen.getByTestId('workflow-search-bar')
  const filters = screen.getByRole('group', { name: 'Status' })
  const results = screen.getByTestId('workflow-results')
  for (const name of ['All', 'Active', 'Inactive']) {
    const button = screen.getByRole('button', { name })
    expect(button.className).toContain('h-10')
    expect(button.className).toContain('border')
  }

  fireEvent.click(screen.getByRole('button', { name: 'Inactive' }))

  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)
  expect(screen.getByTestId('app-main')).toBe(main)
  expect(header.isConnected).toBe(true)
  expect(sidebar.isConnected).toBe(true)
  expect(main.isConnected).toBe(true)
  expect(screen.getByTestId('route-location').textContent).toBe('/workflows?status=inactive')
  expect(search.isConnected).toBe(true)
  expect(filters.isConnected).toBe(true)
  expect(results.isConnected).toBe(true)
  expect(results.contains(search)).toBe(false)
  expect(
    search.compareDocumentPosition(results) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Alpha' })).toBeTruthy()
  expect(results.className).toContain(
    'min-h-[max(14rem,calc(100svh-12rem))]',
  )
  expect(results.getAttribute('aria-busy')).toBe('true')

  const resolve = release as ((response: Response) => void) | null
  if (!resolve) {
    throw new Error('Inactive filter did not request a new page')
  }
  resolve(json(page([])))

  expect(
    await screen.findByRole('heading', { name: 'No matching workflows' }),
  ).toBeTruthy()
  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('app-sidebar')).toBe(sidebar)
  expect(screen.getByTestId('app-main')).toBe(main)
  expect(screen.getByTestId('workflow-search-bar')).toBe(search)
  expect(screen.getByRole('group', { name: 'Status' })).toBe(filters)
  expect(screen.getByTestId('workflow-results')).toBe(results)
  expect(screen.getByTestId('route-location').textContent).toBe('/workflows?status=inactive')
  expect(results.contains(screen.getByRole('heading', { name: 'No matching workflows' }))).toBe(
    true,
  )
  expect(listCalls(calls).some((call) => params(call.url).get('active') === 'false')).toBe(
    true,
  )
})

test('changing pages keeps the current results until the next page arrives', async () => {
  let release: ((response: Response) => void) | null = null
  const calls = installFetch((call) => {
    if (params(call.url).get('page') === '1') {
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    return json({
      content: [workflow(4, 'Alpha', true), workflow(8, 'Beta', true)],
      totalElements: 2,
      totalPages: 2,
      size: 20,
      number: 0,
      empty: false,
    })
  })

  renderAt('/workflows')
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()
  const results = screen.getByTestId('workflow-results')

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(screen.getByRole('link', { name: 'Alpha' })).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Beta' })).toBeTruthy()
  expect(screen.getByTestId('workflow-results')).toBe(results)
  expect(results.className).toContain('min-h-[max(14rem,calc(100svh-12rem))]')
  expect(results.querySelector('.py-14')).toBeNull()
  expect(screen.getByTestId('route-location').textContent).toBe('/workflows?page=1')

  const resolve = release as ((response: Response) => void) | null
  if (!resolve) {
    throw new Error('The next page did not start a request')
  }
  resolve(
    json({
      content: [workflow(9, 'Later', true)],
      totalElements: 2,
      totalPages: 2,
      size: 20,
      number: 1,
      empty: false,
    }),
  )

  expect(await screen.findByRole('link', { name: 'Later' })).toBeTruthy()
  expect(screen.queryByRole('link', { name: 'Alpha' })).toBeNull()
  expect(screen.getByTestId('workflow-results')).toBe(results)
  expect(params(listCalls(calls).at(-1)?.url ?? '').get('page')).toBe('1')
})

test('pagination keeps the search text and active filter', async () => {
  const calls = installFetch((call) => {
    const query = params(call.url)
    const number = Number(query.get('page') ?? '0')
    return json({
      content: [workflow(number === 1 ? 8 : 4, number === 1 ? 'Later' : 'Alpha', true)],
      totalElements: 2,
      totalPages: 2,
      size: 20,
      number,
      empty: false,
    })
  })

  renderAt('/workflows?q=Alpha&status=active')
  expect(await screen.findByRole('link', { name: 'Alpha' })).toBeTruthy()
  const header = screen.getByTestId('app-header')
  const search = screen.getByTestId('workflow-search-bar')
  const filters = screen.getByRole('group', { name: 'Status' })

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(await screen.findByRole('link', { name: 'Later' })).toBeTruthy()
  const listed = listCalls(calls).at(-1)
  expect(params(listed?.url ?? '').get('q')).toBe('Alpha')
  expect(params(listed?.url ?? '').get('active')).toBe('true')
  expect(params(listed?.url ?? '').get('page')).toBe('1')
  expect(screen.getByTestId('route-location').textContent).toBe(
    '/workflows?q=Alpha&status=active&page=1',
  )
  expect(screen.getByTestId('app-header')).toBe(header)
  expect(screen.getByTestId('workflow-search-bar')).toBe(search)
  expect(screen.getByRole('group', { name: 'Status' })).toBe(filters)
  expect((screen.getByLabelText('Search') as HTMLInputElement).value).toBe('Alpha')
  expect(screen.getByRole('button', { name: 'Active' }).getAttribute('aria-pressed')).toBe(
    'true',
  )
})

test('a numeric search is sent as an exact id query', async () => {
  const calls = installFetch(() => json(page([workflow(15, 'Other')])))

  renderAt('/workflows?q=15')
  expect(await screen.findByRole('link', { name: 'Other' })).toBeTruthy()
  expect(params(listCalls(calls).at(-1)?.url ?? '').get('q')).toBe('15')
  expect(params(listCalls(calls).at(-1)?.url ?? '').get('active')).toBeNull()
})
