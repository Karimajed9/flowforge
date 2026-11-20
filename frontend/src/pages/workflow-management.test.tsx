// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { renderApp } from '../test/renderApp.tsx'
import {
  buildHttpRequestConfiguration,
  httpDraftDiffers,
  httpDraftFrom,
} from '../lib/workflowForm.ts'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

type Call = { url: string; method: string; body: unknown }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderAt(path: string) {
  return renderApp(path)
}

function workflow(overrides: Record<string, unknown> = {}) {
  return {
    id: 4,
    name: 'Onboarding',
    description: 'New hire',
    active: false,
    createdAt: '2026-11-16T15:00:00Z',
    ...overrides,
  }
}

function installFetch(
  handler: (call: Call, init?: RequestInit) => Response | Promise<Response>,
) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = init?.body
    const call = {
      url: String(input),
      method: init?.method ?? 'GET',
      body: typeof raw === 'string' ? (JSON.parse(raw) as unknown) : null,
    }
    calls.push(call)
    return handler(call, init)
  })
  return calls
}

function input(id: string): HTMLInputElement {
  const element = document.getElementById(id)
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`Missing input ${id}`)
  }
  return element
}

function workflowPage(content: unknown[] = []) {
  return {
    content,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    size: 20,
    number: 0,
    empty: content.length === 0,
  }
}

function isWorkflowCollection(url: string) {
  return new URL(url, 'http://localhost').pathname === '/api/workflows'
}

function textArea(id: string): HTMLTextAreaElement {
  const element = document.getElementById(id)
  if (!(element instanceof HTMLTextAreaElement)) {
    throw new Error(`Missing textarea ${id}`)
  }
  return element
}

test('workflow list shows saved workflows and their ids', async () => {
  installFetch((call) => {
    if (isWorkflowCollection(call.url) && call.method === 'GET') {
      return json(workflowPage([workflow()]))
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows')

  const link = await screen.findByRole('link', { name: 'Onboarding' })
  expect(link.getAttribute('href')).toBe('/workflows/4')
  expect(link.closest('li')?.textContent).toContain('Inactive')
  expect(screen.getByText('4')).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Open workflow' }).getAttribute('href')).toBe(
    '/workflows/4',
  )
})

test('workflow list shows loading, empty, and error states', async () => {
  const pending: Array<(response: Response) => void> = []
  installFetch(
    () =>
      new Promise<Response>((resolve) => {
        pending.push(resolve)
      }),
  )

  const view = renderAt('/workflows')
  expect(await screen.findByText('Loading workflows')).toBeTruthy()
  for (const resolve of pending) {
    resolve(json(workflowPage()))
  }
  expect(await screen.findByRole('heading', { name: 'No workflows yet' })).toBeTruthy()

  cleanup()
  view.unmount()
  installFetch(() => Promise.reject(new TypeError('Failed to fetch')))
  renderAt('/workflows')
  expect(
    await screen.findByRole('heading', { name: 'Something went wrong' }),
  ).toBeTruthy()
  expect(screen.getByText('The API could not be reached')).toBeTruthy()
})

test('creating a workflow shows backend errors and opens the new workflow', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'GET' && isWorkflowCollection(call.url)) {
      return json(workflowPage())
    }
    if (call.method === 'POST' && call.url === '/api/workflows') {
      if ((call.body as { name?: string }).name === 'Taken') {
        return json({ detail: 'Name is already used' }, 400)
      }
      return json(
        workflow({
          id: 7,
          name: 'Onboarding',
          description: 'New hire',
        }),
        201,
      )
    }
    if (call.url === '/api/workflows/7') {
      return json(workflow({ id: 7, name: 'Onboarding', description: 'New hire' }))
    }
    if (call.url === '/api/workflows/7/steps') {
      return json([])
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows')
  expect(await screen.findByRole('heading', { name: 'No workflows yet' })).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))
  expect(await screen.findByText('Name is required')).toBeTruthy()
  expect(calls.some((call) => call.method === 'POST')).toBe(false)

  fireEvent.change(input('workflow-name'), { target: { value: 'Taken' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))
  expect(await screen.findByText('Name is already used')).toBeTruthy()

  fireEvent.change(input('workflow-name'), { target: { value: '  Onboarding  ' } })
  fireEvent.change(textArea('workflow-description'), {
    target: { value: 'New hire' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))

  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.getByText('Inactive')).toBeTruthy()
  expect(screen.getByRole('link', { name: 'View executions' }).getAttribute('href')).toBe(
    '/executions?workflowId=7',
  )
  expect(calls.filter((call) => call.method === 'POST').at(-1)?.body).toEqual({
    name: 'Onboarding',
    description: 'New hire',
  })
})

test('workflow detail loads the workflow and ordered steps', async () => {
  const pending: Array<{ url: string; resolve: (response: Response) => void }> = []
  installFetch(
    (call) =>
      new Promise<Response>((resolve) => {
        pending.push({ url: call.url, resolve })
      }),
  )

  renderAt('/workflows/4')
  expect(await screen.findByText('Loading workflow')).toBeTruthy()
  await waitFor(() => {
    expect(pending.map((gate) => gate.url).sort()).toEqual([
      '/api/workflows/4',
      '/api/workflows/4/steps',
    ])
  })

  for (const gate of pending) {
    if (gate.url === '/api/workflows/4') {
      gate.resolve(json(workflow({ active: true })))
    }
    if (gate.url === '/api/workflows/4/steps') {
      gate.resolve(
        json([
          {
            id: 9,
            name: 'Call API',
            stepType: 'HTTP_REQUEST',
            position: 2,
            configuration: { method: 'GET', url: 'https://example.com/hook' },
          },
          {
            id: 3,
            name: 'Check fields',
            stepType: 'VALIDATE_FIELDS',
            position: 1,
            configuration: { requiredFields: ['email'] },
          },
        ]),
      )
    }
  }

  expect(await screen.findByRole('heading', { name: 'Onboarding' })).toBeTruthy()
  expect(screen.getByText('Active')).toBeTruthy()
  const names = screen
    .getAllByRole('heading', { level: 3 })
    .map((heading) => heading.textContent)
  expect(names).toEqual([
    'Check fields',
    'Call API',
    'Add field check',
    'Add HTTPS GET',
    'Add condition',
  ])
  expect(screen.getByText('Required fields: email')).toBeTruthy()
  expect(screen.getByText('GET https://example.com/hook')).toBeTruthy()
  expect(screen.getByText('Step count').parentElement?.textContent).toContain('2')
})

test('a workflow with no steps shows an empty state', async () => {
  installFetch((call) => {
    if (call.url === '/api/workflows/4') {
      return json(workflow())
    }
    return json([])
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'No steps yet' })).toBeTruthy()
  expect(screen.getByText('Step count').parentElement?.textContent).toContain('0')
})

test('workflow detail shows a load error', async () => {
  installFetch(() => Promise.reject(new TypeError('Failed to fetch')))

  renderAt('/workflows/4')
  expect(await screen.findByText('The API could not be reached')).toBeTruthy()
})

test('adding a VALIDATE_FIELDS step uses the returned step', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    expect(call.body).toEqual({
      name: 'Check fields',
      stepType: 'VALIDATE_FIELDS',
      position: 1,
      configuration: { requiredFields: ['email', 'name'] },
    })
    return json(
      {
        id: 3,
        name: 'Check fields',
        stepType: 'VALIDATE_FIELDS',
        position: 1,
        configuration: { requiredFields: ['email', 'name'] },
      },
      201,
    )
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'No steps yet' })).toBeTruthy()
  fireEvent.change(input('field-step-name'), { target: { value: 'Check fields' } })
  fireEvent.change(input('field-step-position'), { target: { value: '1' } })
  fireEvent.change(textArea('field-step-fields'), {
    target: { value: 'email, name' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Add field check' }))

  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  expect(screen.getByText('Required fields: email, name')).toBeTruthy()
  expect(screen.getByText('VALIDATE_FIELDS')).toBeTruthy()
})

test('adding an HTTPS GET step sends method GET', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    expect(call.body).toEqual({
      name: 'Call API',
      stepType: 'HTTP_REQUEST',
      position: 2,
      configuration: { method: 'GET', url: 'https://example.com/orders' },
    })
    return json(
      {
        id: 8,
        name: 'Call API',
        stepType: 'HTTP_REQUEST',
        position: 2,
        configuration: { method: 'GET', url: 'https://example.com/orders' },
      },
      201,
    )
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Add HTTPS GET' })).toBeTruthy()
  expect((document.getElementById('http-step-method') as HTMLSelectElement).value).toBe('GET')
  expect(screen.queryByLabelText('JSON body')).toBeNull()
  fireEvent.change(input('http-step-name'), { target: { value: 'Call API' } })
  fireEvent.change(input('http-step-position'), { target: { value: '2' } })
  fireEvent.change(input('http-step-url'), {
    target: { value: 'https://example.com/orders' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Add HTTPS GET' }))

  expect(await screen.findByRole('heading', { name: 'Call API' })).toBeTruthy()
  expect(screen.getByText('GET https://example.com/orders')).toBeTruthy()
})

test('adding an HTTPS POST step sends static JSON with its types', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    expect(call.body).toEqual({
      name: 'Notify',
      stepType: 'HTTP_REQUEST',
      position: 1,
      configuration: {
        method: 'POST',
        url: 'https://example.com/orders',
        bodySource: 'STATIC_JSON',
        body: { count: 2, ok: true, label: 'ada', note: null },
      },
    })
    return json(
      {
        id: 8,
        name: 'Notify',
        stepType: 'HTTP_REQUEST',
        position: 1,
        configuration: {
          method: 'POST',
          url: 'https://example.com/orders',
          bodySource: 'STATIC_JSON',
          body: { count: 2, ok: true, label: 'ada', note: null },
        },
      },
      201,
    )
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Add HTTPS GET' })).toBeTruthy()
  fireEvent.change(input('http-step-name'), { target: { value: 'Notify' } })
  fireEvent.change(input('http-step-position'), { target: { value: '1' } })
  fireEvent.change(document.getElementById('http-step-method') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  fireEvent.change(input('http-step-url'), {
    target: { value: 'https://example.com/orders' },
  })
  fireEvent.change(document.getElementById('http-step-body') as HTMLTextAreaElement, {
    target: { value: '{"count":2,"ok":true,"label":"ada","note":null}' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Add HTTPS POST' }))

  expect(await screen.findByText('POST https://example.com/orders')).toBeTruthy()
  expect(screen.getByText('Static JSON')).toBeTruthy()
})

test('invalid URLs and unsupported methods are rejected before a request', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    return json({ detail: 'should not be called' }, 500)
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('Method')).toBeTruthy()
  fireEvent.change(input('http-step-name'), { target: { value: 'Call API' } })
  fireEvent.change(input('http-step-position'), { target: { value: '2' } })
  fireEvent.change(input('http-step-url'), {
    target: { value: 'http://example.com/hook' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Add HTTPS GET' }))

  expect(await screen.findByText('A valid HTTPS URL is required')).toBeTruthy()
  expect(calls.some((call) => call.method === 'POST')).toBe(false)
  expect(buildHttpRequestConfiguration('POST', 'https://example.com/hook', {
    source: 'STATIC_JSON',
    text: '{',
  })).toEqual({
    ok: false,
    message: 'JSON is invalid',
  })
  expect(buildHttpRequestConfiguration('GET', 'https://example.com/hook')).toEqual({
    ok: true,
    configuration: { method: 'GET', url: 'https://example.com/hook' },
  })
  expect(buildHttpRequestConfiguration('PUT', 'https://example.com/hook').ok).toBe(false)
  expect(
    buildHttpRequestConfiguration('GET', 'https://user:secret@example.com/hook').ok,
  ).toBe(false)
})

const UNSAFE_INTEGER =
  "Integer exceeds JavaScript's safe range (±9007199254740991). Send it as a JSON string if the receiver accepts that."

test('static JSON rejects integers JavaScript cannot store exactly', () => {
  const url = 'https://example.com/hook'
  const saved = buildHttpRequestConfiguration('POST', url, {
    source: 'STATIC_JSON',
    text: '{"count":2,"ok":true,"label":"ada","note":null,"ratio":1.5,"items":[1,false,null]}',
  })

  expect(saved).toEqual({
    ok: true,
    configuration: {
      method: 'POST',
      url,
      bodySource: 'STATIC_JSON',
      body: {
        count: 2,
        ok: true,
        label: 'ada',
        note: null,
        ratio: 1.5,
        items: [1, false, null],
      },
    },
  })
  expect(buildHttpRequestConfiguration('GET', url)).toEqual({
    ok: true,
    configuration: { method: 'GET', url },
  })
  for (const text of [
    '9007199254740991',
    '-9007199254740991',
    '1e2',
    '1.5',
    '0.1',
    '{"id":"9007199254740993","note":"1e+16"}',
    '{"order":{"id":"9007199254740993"},"tags":["9007199254740992"]}',
  ]) {
    expect(buildHttpRequestConfiguration('POST', url, { source: 'STATIC_JSON', text }).ok, text).toBe(
      true,
    )
  }
  for (const text of [
    '9007199254740992',
    '9007199254740993',
    '-9007199254740993',
    '{"order":{"id":9007199254740993}}',
    '[1, -9007199254740993]',
    '[{"id":9007199254740992}]',
    '9.007199254740993e15',
    '1e+16',
    '1.000e16',
  ]) {
    expect(buildHttpRequestConfiguration('POST', url, { source: 'STATIC_JSON', text }), text).toEqual({
      ok: false,
      message: UNSAFE_INTEGER,
    })
  }

  const loaded = {
    method: 'POST' as const,
    url,
    bodySource: 'STATIC_JSON' as const,
    body: { count: 2, ok: true, label: 'ada', note: null },
  }
  const draft = httpDraftFrom(loaded)
  expect(httpDraftDiffers(loaded, draft)).toBe(false)
  expect(
    buildHttpRequestConfiguration(draft.method, draft.url, {
      source: draft.bodySource,
      text: draft.bodyText,
    }),
  ).toEqual({ ok: true, configuration: loaded })

  const alreadyRounded = httpDraftFrom({ ...loaded, body: 9007199254740992 })
  expect(alreadyRounded.bodyText).toBe('9007199254740992')
  expect(
    buildHttpRequestConfiguration('POST', url, {
      source: 'STATIC_JSON',
      text: alreadyRounded.bodyText,
    }).ok,
  ).toBe(false)
})

test('adding a POST step shows the safe-integer error and does not save', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    return json({ detail: 'should not be called' }, 500)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Add HTTPS GET' })).toBeTruthy()
  fireEvent.change(input('http-step-name'), { target: { value: 'Notify' } })
  fireEvent.change(input('http-step-position'), { target: { value: '1' } })
  fireEvent.change(document.getElementById('http-step-method') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  fireEvent.change(input('http-step-url'), { target: { value: 'https://example.com/orders' } })
  fireEvent.change(textArea('http-step-body'), { target: { value: '{"id":9007199254740993}' } })
  fireEvent.click(screen.getByRole('button', { name: 'Add HTTPS POST' }))

  expect(await screen.findByText(UNSAFE_INTEGER)).toBeTruthy()
  expect(calls.some((call) => call.method === 'POST')).toBe(false)
})

test('step API validation failures stay on the form', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    return json({ detail: 'A step already exists at this position' }, 409)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'No steps yet' })).toBeTruthy()
  fireEvent.change(input('field-step-name'), { target: { value: 'Check fields' } })
  fireEvent.change(input('field-step-position'), { target: { value: '1' } })
  fireEvent.change(textArea('field-step-fields'), { target: { value: 'email' } })
  fireEvent.click(screen.getByRole('button', { name: 'Add field check' }))

  expect(
    await screen.findByText('A step already uses this position. Choose another one.'),
  ).toBeTruthy()
  expect(input('field-step-name').value).toBe('Check fields')
  expect(input('field-step-position').value).toBe('1')
  expect(textArea('field-step-fields').value).toBe('email')
  expect(screen.getByRole('heading', { name: 'No steps yet' })).toBeTruthy()
})

test('a position-conflict title uses the same message on the HTTPS form', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    return json({ title: 'Workflow step position conflict' }, 409)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'No steps yet' })).toBeTruthy()
  fireEvent.change(input('http-step-name'), { target: { value: 'Call API' } })
  fireEvent.change(input('http-step-position'), { target: { value: '2' } })
  fireEvent.change(input('http-step-url'), {
    target: { value: 'https://example.com/hook' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Add HTTPS GET' }))

  expect(
    await screen.findByText('A step already uses this position. Choose another one.'),
  ).toBeTruthy()
  expect(screen.queryByText('Request failed (409)')).toBeNull()
  expect(input('http-step-name').value).toBe('Call API')
  expect(input('http-step-position').value).toBe('2')
  expect(input('http-step-url').value).toBe('https://example.com/hook')
})

test('a bare conflict is not treated as a duplicate position', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    return json({ timestamp: '2026-11-17T00:00:00Z', status: 409, error: 'Conflict' }, 409)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'No steps yet' })).toBeTruthy()
  fireEvent.change(input('field-step-name'), { target: { value: 'Check fields' } })
  fireEvent.change(input('field-step-position'), { target: { value: '1' } })
  fireEvent.change(textArea('field-step-fields'), { target: { value: 'email' } })
  fireEvent.click(screen.getByRole('button', { name: 'Add field check' }))

  expect(await screen.findByText('The step was not added')).toBeTruthy()
  expect(screen.queryByText('Request failed (409)')).toBeNull()
  expect(screen.queryByText('A step already uses this position. Choose another one.')).toBeNull()
  expect(
    screen.queryByText('This workflow was changed elsewhere. Refresh the page and try again.'),
  ).toBeNull()
  expect(input('field-step-name').value).toBe('Check fields')
  expect(input('field-step-position').value).toBe('1')
  expect(textArea('field-step-fields').value).toBe('email')
})

test('a stale revision conflict keeps its own message on the add form', async () => {
  installFetch((call) => {
    if (call.method === 'GET' && call.url === '/api/workflows/4') {
      return json(workflow())
    }
    if (call.method === 'GET') {
      return json([])
    }
    return json(
      {
        title: 'Step definition conflict',
        detail: 'This workflow changed. Refresh and try again.',
      },
      409,
    )
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'No steps yet' })).toBeTruthy()
  fireEvent.change(input('field-step-name'), { target: { value: 'Check fields' } })
  fireEvent.change(input('field-step-position'), { target: { value: '1' } })
  fireEvent.change(textArea('field-step-fields'), { target: { value: 'email' } })
  fireEvent.click(screen.getByRole('button', { name: 'Add field check' }))

  expect(
    await screen.findByText('This workflow changed. Refresh and try again.'),
  ).toBeTruthy()
  expect(screen.queryByText('A step already uses this position. Choose another one.')).toBeNull()
  expect(input('field-step-name').value).toBe('Check fields')
})

test('status updates follow the backend response', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      expect(call.body).toEqual({ active: true })
      return json(workflow({ active: true }))
    }
    if (call.url === '/api/workflows/4/steps') {
      return json([])
    }
    return json(workflow({ active: false }))
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('Inactive')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Activate' }))
  expect(await screen.findByText('Workflow is active')).toBeTruthy()
  expect(screen.getByText('Active')).toBeTruthy()
})

test('a failed status update leaves the current status in place', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({ detail: 'Workflow not found' }, 404)
    }
    if (call.url.endsWith('/steps')) {
      return json([])
    }
    return json(workflow({ active: true }))
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('Active')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
  expect(await screen.findByText('Workflow not found')).toBeTruthy()
  expect(screen.getByText('Active')).toBeTruthy()
  expect(screen.queryByText('Workflow is inactive')).toBeNull()
})
