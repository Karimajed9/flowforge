// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { MemoryRouter, useLocation } from 'react-router'
import { AppRoutes } from '../App.tsx'

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

function workflow(active = true) {
  return {
    id: 4,
    name: 'Onboarding',
    description: 'New hire',
    active,
    createdAt: '2026-11-16T15:00:00Z',
  }
}

function bootConflict() {
  return {
    timestamp: '2026-11-17T20:10:00.000+00:00',
    status: 409,
    error: 'Conflict',
    path: '/api/workflows/4/executions',
  }
}

function execution(id = 21) {
  return {
    id,
    workflowId: 4,
    status: 'RECEIVED',
    createdAt: '2026-11-16T15:04:00Z',
    failureReason: null,
  }
}

function RouteLocation() {
  const location = useLocation()
  return <p data-testid="route-location">{location.pathname}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
      <RouteLocation />
    </MemoryRouter>,
  )
}

test('the editor validates JSON before starting a real execution', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.queryByText('This starts a real execution. It is not a dry run.')).toBeNull()
  expect(screen.getByRole('button', { name: 'Run workflow' }).className).toContain('bg-teal-500')

  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
  expect(screen.getByText('This starts a real execution. It is not a dry run.')).toBeTruthy()
  expect(screen.getByLabelText('Payload')).toBeTruthy()

  const opener = screen.getByRole('button', { name: 'Run workflow' })
  expect(opener.className).toContain('bg-teal-500')

  fireEvent.change(screen.getByLabelText('Payload'), { target: { value: '{invalid' } })
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
  const syntax = await screen.findByRole('alert')
  expect(syntax.textContent).toContain('Enter a valid JSON object.')
  expect(syntax.textContent).toContain('Check the commas, quotes, and braces.')
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{invalid')
  expect(calls.some((url) => url.includes('/executions'))).toBe(false)

  for (const value of ['[1]', '"text"', '1', 'null']) {
    fireEvent.change(screen.getByLabelText('Payload'), { target: { value } })
    fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      'The payload must be a JSON object.',
    )
    expect(screen.getByLabelText('Payload')).toHaveProperty('value', value)
  }
  expect(calls.some((url) => url.includes('/executions'))).toBe(false)
})

test('submit stays blocked until the execution is created, then opens it', async () => {
  let release: ((response: Response) => void) | null = null
  const posts: Array<{ key: string | null; csrf: string | null; body: string | undefined }> = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      posts.push({
        key: new Headers(init?.headers).get('Idempotency-Key'),
        csrf: new Headers(init?.headers).get('X-XSRF-TOKEN'),
        body: typeof init?.body === 'string' ? init.body : undefined,
      })
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    if (url === '/api/executions/21') {
      return Promise.resolve(json(execution()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
  fireEvent.change(screen.getByLabelText('Payload'), { target: { value: '{"amount":1}' } })
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))

  const running = await screen.findByRole('button', { name: 'Starting execution' })
  expect((running as HTMLButtonElement).disabled).toBe(true)
  expect(screen.getByRole('status').textContent).toBe('Starting the execution.')
  fireEvent.click(running)
  expect(posts).toHaveLength(1)
  expect(posts[0]?.csrf).toBe('test-csrf')
  expect(posts[0]?.body).toBe('{"amount":1}')
  expect(posts[0]?.key).toMatch(/^[A-Za-z0-9._:-]{1,128}$/)

  const resolve = release as ((response: Response) => void) | null
  if (!resolve) {
    throw new Error('The run did not start')
  }
  resolve(json(execution(), 201))

  expect(await screen.findByRole('heading', { name: 'Execution 21' })).toBeTruthy()
  expect(screen.getByTestId('route-location').textContent).toBe('/executions/21')
  expect(
    screen.getByText(
      'This execution was accepted and has not finished. It is not a completed workflow.',
    ),
  ).toBeTruthy()
  expect(screen.getByText('RECEIVED')).toBeTruthy()
})

test('an API error stays on the editor and a retry reuses the idempotency key', async () => {
  let failed = true
  const keys: Array<string | null> = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      if (failed) {
        return Promise.resolve(json({ detail: 'Workflow is inactive' }, 409))
      }
      return Promise.resolve(json(execution(22), 201))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    if (url === '/api/executions/22') {
      return Promise.resolve(json(execution(22)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
  fireEvent.change(screen.getByLabelText('Payload'), { target: { value: '{"amount":1}' } })
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'This workflow is inactive. Activate it before running.',
  )
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"amount":1}')
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  expect(screen.getByTestId('route-location').textContent).toBe('/workflows/4')

  failed = false
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
  expect(await screen.findByRole('heading', { name: 'Execution 22' })).toBeTruthy()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
})

function openRun(value = '{"amount":1}') {
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
  fireEvent.change(screen.getByLabelText('Payload'), { target: { value } })
  fireEvent.click(screen.getByRole('button', { name: 'Run workflow' }))
}

test('an idempotency conflict names the previous data and keeps the payload', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(
        json(
          {
            title: 'Conflict',
            detail: 'Idempotency key was already used with a different payload',
          },
          409,
        ),
      )
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun()

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'This submission key was previously used with different data.',
  )
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"amount":1}')
  expect(screen.queryByText('Request failed (409)')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  expect(screen.getByTestId('route-location').textContent).toBe('/workflows/4')
})

test('a database idempotency conflict uses the same explanation', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(
        json(
          {
            title: 'Idempotency conflict',
            detail: 'An execution already exists with this idempotency key',
          },
          409,
        ),
      )
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun()

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'This submission key was already used.',
  )
  expect(screen.queryByText('An execution already exists with this idempotency key')).toBeNull()
  expect(
    screen.queryByText('This submission key was previously used with different data.'),
  ).toBeNull()
})

test('an unrelated conflict is not described as an inactive workflow', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(json({ detail: 'Workflow step position conflict' }, 409))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun()

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'The workflow could not be started.',
  )
  expect(screen.queryByText('This workflow is inactive. Activate it before running.')).toBeNull()
  expect(screen.queryByText('Workflow step position conflict')).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"amount":1}')
})

test('an expired session asks the user to sign in again', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(json({ status: 401, error: 'Unauthorized' }, 401))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun('{"name":"Ada"}')

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'Your session has expired. Please sign in again.',
  )
  expect(screen.queryByText('Request failed (401)')).toBeNull()
  expect(screen.queryByText('Unauthorized')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"name":"Ada"}')
  expect(screen.getByTestId('route-location').textContent).toBe('/workflows/4')
})

test('a forbidden run is rejected without calling it an inactive workflow', async () => {
  let rejected = true
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      if (rejected) {
        return Promise.resolve(json({ status: 403, error: 'Forbidden' }, 403))
      }
      return Promise.resolve(json(execution(23), 201))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    if (url === '/api/executions/23') {
      return Promise.resolve(json(execution(23)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun()

  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'The request was rejected.')
  expect(screen.queryByText('Forbidden')).toBeNull()
  expect(screen.queryByText('This workflow is inactive. Activate it before running.')).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"amount":1}')

  rejected = false
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('heading', { name: 'Execution 23' })).toBeTruthy()
})

test('a network failure keeps the payload and can be retried', async () => {
  let offline = true
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      if (offline) {
        return Promise.reject(new TypeError('Failed to fetch'))
      }
      return Promise.resolve(json(execution(24), 201))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    if (url === '/api/executions/24') {
      return Promise.resolve(json(execution(24)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun('{"offline":true}')

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'The server cannot be reached.',
  )
  expect(screen.queryByText('Failed to fetch')).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"offline":true}')

  offline = false
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('heading', { name: 'Execution 24' })).toBeTruthy()
})

test('a server failure stays friendly and offers retry', async () => {
  let failed = true
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      if (failed) {
        return Promise.resolve(
          json(
            {
              status: 500,
              error: 'Internal Server Error',
              trace: 'java.lang.RuntimeException: secret-token',
            },
            500,
          ),
        )
      }
      return Promise.resolve(json(execution(25), 201))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    if (url === '/api/executions/25') {
      return Promise.resolve(json(execution(25)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun()

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'The service ran into a problem. Try again in a moment.',
  )
  expect(screen.queryByText('Internal Server Error')).toBeNull()
  expect(screen.queryByText(/secret-token/)).toBeNull()
  expect(screen.queryByText(/RuntimeException/)).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"amount":1}')

  failed = false
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('heading', { name: 'Execution 25' })).toBeTruthy()
})

test('a missing workflow uses the friendly not-found message', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(json({ title: 'Not Found', detail: 'Workflow not found' }, 404))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow()))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  openRun()

  const alert = await screen.findByRole('alert')
  expect(alert.textContent).toContain('Workflow not found')
  expect(alert.textContent).toContain(
    "This workflow doesn't exist, or you don't have access to it.",
  )
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"amount":1}')
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
})

test('a failed execution shows its reason and that it finished', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/executions/21') {
      return Promise.resolve(
        json({
          ...execution(),
          status: 'FAILED',
          failureReason: 'worker lost the response',
        }),
      )
    }
    return Promise.resolve(json([]))
  })

  renderAt('/executions/21')
  expect(await screen.findByText('worker lost the response')).toBeTruthy()
  expect(screen.getByText('FAILED')).toBeTruthy()
  expect(screen.getByText('This execution finished with a failure.')).toBeTruthy()
})

test('an inactive workflow maps a detail-less 409 and keeps the payload', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(json(bootConflict(), 409))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow(false)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('Inactive')).toBeTruthy()
  openRun('{"email":"karim@gmail.com"}')

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'This workflow is inactive. Activate it before running.',
  )
  expect(screen.queryByText('The workflow could not be started.')).toBeNull()
  expect(screen.queryByText('Conflict')).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"email":"karim@gmail.com"}')
})

test('the same detail-less 409 stays generic when the workflow is active', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(json(bootConflict(), 409))
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow(true)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('Active')).toBeTruthy()
  openRun('{"email":"karim@gmail.com"}')

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'The workflow could not be started.',
  )
  expect(screen.queryByText('This workflow is inactive. Activate it before running.')).toBeNull()
  expect(screen.getByLabelText('Payload')).toHaveProperty('value', '{"email":"karim@gmail.com"}')
})

test('an inactive workflow still prefers an explicit idempotency conflict', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/workflows/4/executions') {
      return Promise.resolve(
        json(
          {
            title: 'Conflict',
            detail: 'Idempotency key was already used with a different payload',
          },
          409,
        ),
      )
    }
    if (url === '/api/workflows/4') {
      return Promise.resolve(json(workflow(false)))
    }
    return Promise.resolve(json([]))
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('Inactive')).toBeTruthy()
  openRun()

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'This submission key was previously used with different data.',
  )
  expect(screen.queryByText('This workflow is inactive. Activate it before running.')).toBeNull()
})
