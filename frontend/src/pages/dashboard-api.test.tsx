// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { AppRoutes } from '../App.tsx'
import { formatTimestamp } from '../lib/format.ts'

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

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

function execution(overrides: Record<string, unknown> = {}) {
  return {
    id: 21,
    workflowId: 4,
    status: 'SUCCEEDED',
    createdAt: '2026-11-16T15:04:00Z',
    failureReason: null,
    ...overrides,
  }
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

function installFetch(
  handler: (url: string, init?: RequestInit) => Response | Promise<Response>,
) {
  const calls: string[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push(url)
    return handler(url, init)
  })
  return calls
}

test('successful execution load shows the execution fields', async () => {
  const calls = installFetch((url) => {
    if (url === '/api/executions/21') {
      return json(execution())
    }
    if (url === '/api/executions/21/steps') {
      return json([])
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/executions/21')

  expect(
    await screen.findByRole('heading', { name: 'Execution 21' }),
  ).toBeTruthy()
  expect(screen.getAllByText('Workflow 4').length).toBeGreaterThan(0)
  expect(screen.getByText('SUCCEEDED').className).toContain('green')
  expect(
    screen.getByText(formatTimestamp('2026-11-16T15:04:00Z')),
  ).toBeTruthy()
  expect(screen.queryByText('Failure reason')).toBeNull()
  expect(calls).toEqual([
    '/api/executions/21',
    '/api/executions/21/steps',
  ])

  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
  expect(await screen.findByText('Loading execution')).toBeTruthy()
  expect(
    await screen.findByRole('heading', { name: 'Execution 21' }),
  ).toBeTruthy()
  expect(calls.filter((url) => url === '/api/executions/21')).toHaveLength(2)
})

test('execution details show a loading state and then a network error', async () => {
  const pending: Array<(error: unknown) => void> = []
  installFetch(
    () =>
      new Promise<Response>((_resolve, reject) => {
        pending.push(reject)
      }),
  )

  renderAt('/executions/21')

  expect(await screen.findByText('Loading execution')).toBeTruthy()
  for (const rejectFetch of pending) {
    rejectFetch(new TypeError('Failed to fetch'))
  }
  expect(
    await screen.findByRole('heading', { name: 'Something went wrong' }),
  ).toBeTruthy()
  expect(screen.getByText('The API could not be reached')).toBeTruthy()
})

test('a missing execution is a not-found state', async () => {
  installFetch(() => json({ detail: 'Execution not found' }, 404))

  renderAt('/executions/9')

  expect(
    await screen.findByRole('heading', { name: 'Execution not found' }),
  ).toBeTruthy()
  expect(screen.getAllByText('Execution not found').length).toBeGreaterThan(0)
  expect(screen.queryByRole('heading', { name: 'Something went wrong' })).toBeNull()
})

test('step history is shown in position order', async () => {
  installFetch((url) => {
    if (url === '/api/executions/21') {
      return json(execution())
    }
    return json([
      {
        stepId: 8,
        name: 'Call API',
        stepType: 'HTTP_REQUEST',
        position: 2,
        status: 'SUCCEEDED',
        attemptNumber: 1,
        failureReason: null,
        startedAt: '2026-11-16T15:04:03Z',
        updatedAt: '2026-11-16T15:04:04Z',
        leaseUntil: null,
      },
      {
        stepId: 3,
        name: 'Check fields',
        stepType: 'VALIDATE_FIELDS',
        position: 1,
        status: 'SUCCEEDED',
        attemptNumber: 1,
        failureReason: null,
        startedAt: '2026-11-16T15:04:01Z',
        updatedAt: '2026-11-16T15:04:02Z',
        leaseUntil: null,
      },
    ])
  })

  renderAt('/executions/21')

  expect(
    await screen.findByRole('heading', { name: 'Check fields' }),
  ).toBeTruthy()
  const names = screen
    .getAllByRole('heading', { level: 3 })
    .map((heading) => heading.textContent)
  expect(names).toEqual(['Check fields', 'Call API'])
  expect(screen.getByText('VALIDATE_FIELDS')).toBeTruthy()
  expect(screen.getByText('HTTP_REQUEST')).toBeTruthy()
})

test('NOT_STARTED steps show null attempt and timestamps', async () => {
  installFetch((url) => {
    if (url === '/api/executions/21') {
      return json(execution())
    }
    return json([
      {
        stepId: 3,
        name: 'Waiting step',
        stepType: 'HTTP_REQUEST',
        position: 1,
        status: 'NOT_STARTED',
        attemptNumber: null,
        failureReason: null,
        startedAt: null,
        updatedAt: null,
        leaseUntil: null,
      },
    ])
  })

  renderAt('/executions/21')

  const heading = await screen.findByRole('heading', { name: 'Waiting step' })
  const card = heading.closest('li')
  expect(card?.textContent).toContain('NOT_STARTED')
  expect(card?.textContent).toContain('Attempt')
  expect(card?.textContent).toContain('—')
  expect(screen.getByText('NOT_STARTED').className).toContain('slate')
  expect(card?.textContent).not.toContain('Failure reason')
})

test('FAILED execution and step show their reasons', async () => {
  installFetch((url) => {
    if (url === '/api/executions/21') {
      return json(
        execution({
          status: 'FAILED',
          failureReason: 'worker lost the response',
        }),
      )
    }
    return json([
      {
        stepId: 8,
        name: 'Call API',
        stepType: 'HTTP_REQUEST',
        position: 1,
        status: 'FAILED',
        attemptNumber: 2,
        failureReason: 'HTTP status 500',
        startedAt: '2026-11-16T15:04:03Z',
        updatedAt: '2026-11-16T15:04:04Z',
        leaseUntil: '2026-11-16T15:05:00Z',
      },
    ])
  })

  renderAt('/executions/21')

  expect(await screen.findByText('worker lost the response')).toBeTruthy()
  expect(screen.getByText('HTTP status 500')).toBeTruthy()
  expect(screen.getAllByText('FAILED')[0]?.className).toContain('red')
  expect(
    screen.getByText(formatTimestamp('2026-11-16T15:05:00Z')),
  ).toBeTruthy()
})

test('workflow execution history paginates with the query string', async () => {
  const calls: string[] = []
  installFetch((url) => {
    calls.push(url)
    if (url === '/api/workflows/4') {
      return json(workflow())
    }
    const page = new URL(url, 'http://localhost').searchParams.get('page')
    return json({
      content: [
        execution({
          id: page === '1' ? 22 : 21,
          createdAt: '2026-11-16T15:04:00Z',
        }),
      ],
      totalElements: 21,
      totalPages: 2,
      size: 20,
      number: page === '1' ? 1 : 0,
      empty: false,
    })
  })

  renderAt('/executions?workflowId=4&page=0')

  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'Execution 21' })).toBeTruthy()
  expect(screen.getByText('Page 1 of 2')).toBeTruthy()
  expect(calls.some((url) => url.includes('/executions?page=0&size=20'))).toBe(
    true,
  )

  fireEvent.click(screen.getByRole('button', { name: 'Next' }))

  expect(
    await screen.findByRole('heading', { name: 'Execution 22' }),
  ).toBeTruthy()
  expect(screen.getByText('Page 2 of 2')).toBeTruthy()
  expect(calls.some((url) => url.includes('/executions?page=1&size=20'))).toBe(
    true,
  )

  fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
  expect(
    await screen.findByRole('heading', { name: 'Execution 21' }),
  ).toBeTruthy()
})

test('navigating away cancels the execution request', async () => {
  const aborted: string[] = []
  installFetch((url, init) => {
    const path = new URL(url, 'http://localhost').pathname
    if (path === '/api/dashboard/overview') {
      return json({
        totalWorkflows: 0,
        activeWorkflows: 0,
        totalExecutions: 0,
        succeededExecutions: 0,
        failedExecutions: 0,
        successRate: null,
        windowDays: 7,
      })
    }
    if (path === '/api/executions') {
      return json({
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 20,
        number: 0,
        empty: true,
      })
    }
    return new Promise<Response>((_resolve, reject) => {
      const abort = () => {
        aborted.push(url)
        reject(new DOMException('Aborted', 'AbortError'))
      }
      if (init?.signal?.aborted) {
        abort()
        return
      }
      init?.signal?.addEventListener('abort', abort)
    })
  })

  renderAt('/executions/21')
  expect(await screen.findByText('Loading execution')).toBeTruthy()

  fireEvent.click(screen.getByRole('link', { name: 'Overview' }))

  expect(
    await screen.findByRole('heading', { name: 'Overview' }),
  ).toBeTruthy()
  await waitFor(() => {
    expect(aborted).toEqual(
      expect.arrayContaining([
        '/api/executions/21',
        '/api/executions/21/steps',
      ]),
    )
  })
})

test('a workflow with no executions shows an empty state', async () => {
  installFetch((url) => {
    if (url === '/api/workflows/4') {
      return json(workflow())
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

  expect(
    await screen.findByRole('heading', { name: 'No executions for this workflow' }),
  ).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
})
