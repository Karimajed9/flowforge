// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { renderApp, renderRouter } from '../test/renderApp.tsx'
import { STALE_ELSEWHERE } from '../components/editorFeedback.ts'
import { UNSAVED_NAVIGATION } from '../components/unsavedNavigation.ts'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
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

const workflow = {
  id: 4,
  name: 'Onboarding',
  description: 'New hire',
  active: true,
  createdAt: '2026-11-16T15:00:00Z',
  stepRevision: 3,
}

const steps = [
  {
    id: 3,
    name: 'Check fields',
    stepType: 'VALIDATE_FIELDS',
    position: 1,
    configuration: { requiredFields: ['email'] },
  },
  {
    id: 9,
    name: 'Call API',
    stepType: 'HTTP_REQUEST',
    position: 2,
    configuration: { method: 'GET', url: 'https://example.com/hook' },
  },
  {
    id: 11,
    name: 'Confirm',
    stepType: 'VALIDATE_FIELDS',
    position: 3,
    configuration: { requiredFields: ['ready'] },
  },
]

function installFetch(handler: (call: Call) => Response | Promise<Response>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = init?.body
    const call = {
      url: String(input),
      method: init?.method ?? 'GET',
      body: typeof raw === 'string' ? (JSON.parse(raw) as unknown) : null,
    }
    calls.push(call)
    if (call.url === '/api/workflows/4' && call.method === 'GET') {
      return Promise.resolve(json(workflow))
    }
    if (call.url === '/api/workflows/4/steps' && call.method === 'GET') {
      return Promise.resolve(json(steps))
    }
    return Promise.resolve(handler(call))
  })
  return calls
}

test('editing a validation step saves the name and fields', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: {
          ...steps[0],
          name: 'Customer email',
          configuration: { requiredFields: ['email', 'name'] },
        },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  expect(screen.getByText('Position 1')).toBeTruthy()
  expect(screen.getByText('Position 2')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.change(document.getElementById('step-fields-3') as HTMLTextAreaElement, {
    target: { value: 'email, name' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByRole('heading', { name: 'Customer email' })).toBeTruthy()
  const saved = calls.find((call) => call.method === 'PATCH')
  expect(saved?.url).toBe('/api/workflows/4/steps/3')
  expect(saved?.body).toEqual({
    name: 'Customer email',
    configuration: { requiredFields: ['email', 'name'] },
    revision: 3,
  })
})

test('adding a step advances the revision sent by the next edit', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'POST') {
      return json({
        id: 20,
        name: 'Notify',
        stepType: 'VALIDATE_FIELDS',
        position: 4,
        configuration: { requiredFields: ['ready'] },
      })
    }
    if (call.method === 'PATCH') {
      return json({
        step: {
          ...steps[0],
          name: 'Customer email',
        },
        stepRevision: 5,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.change(document.getElementById('field-step-name') as HTMLInputElement, {
    target: { value: 'Notify' },
  })
  fireEvent.change(document.getElementById('field-step-position') as HTMLInputElement, {
    target: { value: '4' },
  })
  fireEvent.change(document.getElementById('field-step-fields') as HTMLTextAreaElement, {
    target: { value: 'ready' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Add field check' }))
  expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => {
    expect(calls.some((call) => call.method === 'PATCH')).toBe(true)
  })
  expect(calls.find((call) => call.method === 'PATCH')?.body).toMatchObject({
    revision: 4,
  })
})

test('editing an HTTPS GET step keeps the method as GET', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: {
          ...steps[1],
          name: 'Fetch orders',
          configuration: { method: 'GET', url: 'https://example.com/orders' },
        },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByText('GET https://example.com/hook')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Call API' }))
  expect((document.getElementById('step-method-9') as HTMLSelectElement).value).toBe('GET')
  expect(screen.queryByLabelText('JSON body')).toBeNull()
  fireEvent.change(document.getElementById('step-name-9') as HTMLInputElement, {
    target: { value: 'Fetch orders' },
  })
  fireEvent.change(document.getElementById('step-url-9') as HTMLInputElement, {
    target: { value: 'https://example.com/orders' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByRole('heading', { name: 'Fetch orders' })).toBeTruthy()
  expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
    name: 'Fetch orders',
    configuration: { method: 'GET', url: 'https://example.com/orders' },
    revision: 3,
  })
})

test('an invalid step configuration stays in the editor', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'PATCH') {
      return json(
        { title: 'Invalid step', detail: 'Hostname could not be resolved' },
        400,
      )
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Call API' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Call API' }))
  const url = document.getElementById('step-url-9') as HTMLInputElement
  fireEvent.change(url, { target: { value: 'http://example.com' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'A valid HTTPS URL is required',
  )
  expect(url.value).toBe('http://example.com')
  expect(calls.some((call) => call.method === 'PATCH')).toBe(false)

  fireEvent.change(url, { target: { value: 'https://example.com/orders' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect(await screen.findByText('Hostname could not be resolved')).toBeTruthy()
  expect(url.value).toBe('https://example.com/orders')
})

test('delete asks for confirmation before removing a step', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'DELETE') {
      return json({
        steps: [steps[1], steps[2]],
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Delete Check fields' }))
  expect(calls.some((call) => call.method === 'DELETE')).toBe(false)
  expect(screen.getByText(/Runs that already started keep the steps/)).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Keep step' }))
  expect(screen.getByRole('heading', { name: 'Check fields' })).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'Delete Check fields' }))
  fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }))

  await waitFor(() => {
    expect(screen.queryByRole('heading', { name: 'Check fields' })).toBeNull()
  })
  expect(calls.find((call) => call.method === 'DELETE')?.url).toBe(
    '/api/workflows/4/steps/3?revision=3',
  )
  expect(screen.getByRole('heading', { name: 'Call API' })).toBeTruthy()
})

test('reordering several steps sends the full order', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'PUT') {
      return json({
        steps: [
          { ...steps[0], position: 1 },
          { ...steps[2], position: 2 },
          { ...steps[1], position: 3 },
        ],
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Call API' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Move Call API down' }))

  await waitFor(() => {
    expect(calls.some((call) => call.method === 'PUT')).toBe(true)
  })
  expect(calls.find((call) => call.method === 'PUT')).toMatchObject({
    url: '/api/workflows/4/steps/order',
    body: { stepIds: [3, 11, 9], revision: 3 },
  })
  const names = screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
  expect(names.slice(0, 3)).toEqual(['Check fields', 'Confirm', 'Call API'])
  expect(screen.getByText('Position 3')).toBeTruthy()
})

test('a conflict keeps the draft and explains that the workflow changed', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return json(
        {
          title: 'Step definition conflict',
          detail: 'This workflow changed. Refresh and try again.',
        },
        409,
      )
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  const name = document.getElementById('step-name-3') as HTMLInputElement
  fireEvent.change(name, { target: { value: 'Customer email' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'This workflow changed. Refresh and try again.',
  )
  expect(name.value).toBe('Customer email')
  expect(screen.getByRole('heading', { name: 'Check fields' })).toBeTruthy()
})

test('unsaved edits warn before they are discarded', async () => {
  installFetch(() => json({ detail: 'Not found' }, 404))
  const confirm = vi.fn(() => false)
  window.confirm = confirm

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  const name = document.getElementById('step-name-3') as HTMLInputElement
  fireEvent.change(name, { target: { value: 'Customer email' } })

  const unload = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unload)
  expect(unload.defaultPrevented).toBe(true)

  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
  expect(confirm).toHaveBeenCalledWith('Unsaved step changes will be lost.')
  expect(name.value).toBe('Customer email')

  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
  expect(document.getElementById('step-name-3')).toBeNull()
})

test('save shows a loading state until the response arrives', async () => {
  let release: ((response: Response) => void) | null = null
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  const saving = await screen.findByRole('button', { name: 'Saving' })
  expect((saving as HTMLButtonElement).disabled).toBe(true)

  const resolve = release as ((response: Response) => void) | null
  if (!resolve) {
    throw new Error('Save did not start')
  }
  resolve(
    json({
      step: { ...steps[0], name: 'Customer email' },
      stepRevision: 4,
    }),
  )
  expect(await screen.findByRole('heading', { name: 'Customer email' })).toBeTruthy()
})

test('an empty conflict on save says the workflow changed elsewhere', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return new Response(null, { status: 409 })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  const name = document.getElementById('step-name-3') as HTMLInputElement
  fireEvent.change(name, { target: { value: 'Customer email' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByRole('alert')).toHaveProperty('textContent', STALE_ELSEWHERE)
  expect(screen.queryByText('Request failed (409)')).toBeNull()
  expect(name.value).toBe('Customer email')
})

test('a conflict without a reason on save says the workflow changed elsewhere', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({ timestamp: '2026-11-17T00:00:00Z', status: 409, error: 'Conflict' }, 409)
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByRole('alert')).toHaveProperty('textContent', STALE_ELSEWHERE)
})

test('a structured reorder conflict keeps the server message', async () => {
  installFetch((call) => {
    if (call.method === 'PUT') {
      return json(
        {
          title: 'Step definition conflict',
          detail: 'The step list does not match this workflow.',
        },
        409,
      )
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Move Call API up' }))

  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'The step list does not match this workflow.',
  )
  expect(screen.queryByText(STALE_ELSEWHERE)).toBeNull()
})

test('browser back and forward ask once and cancel keeps the step draft', async () => {
  installFetch(() => json({ detail: 'Not found' }, 404))
  const confirm = vi.fn(() => false)
  window.confirm = confirm
  const { router } = renderRouter(['/workflows', '/workflows/4'])

  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  const clean = router.navigate(-1)
  await clean
  expect(confirm).not.toHaveBeenCalled()
  expect(router.state.location.pathname).toBe('/workflows')

  await router.navigate(1)
  expect(router.state.location.pathname).toBe('/workflows/4')
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  const name = document.getElementById('step-name-3') as HTMLInputElement
  fireEvent.change(name, { target: { value: 'Customer email' } })

  const blocked = router.navigate(-1)
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
  expect(confirm).toHaveBeenCalledWith(UNSAVED_NAVIGATION)
  await blocked
  expect(router.state.location.pathname).toBe('/workflows/4')
  expect(name.value).toBe('Customer email')
})

test('browser forward asks once and cancel keeps the new workflow draft', async () => {
  installFetch((call) => {
    if (call.url.startsWith('/api/workflows') && call.method === 'GET' && !call.url.includes('/4')) {
      return json({
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 20,
        number: 0,
        empty: true,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })
  const confirm = vi.fn(() => false)
  window.confirm = confirm
  const { router } = renderRouter(['/workflows', '/workflows/4'], { index: 0 })

  const name = (await screen.findByLabelText('Name')) as HTMLInputElement
  const unloaded = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unloaded)
  expect(unloaded.defaultPrevented).toBe(false)

  fireEvent.change(name, { target: { value: 'Payroll' } })
  const blocked = router.navigate(1)
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
  expect(confirm).toHaveBeenCalledWith(UNSAVED_NAVIGATION)
  await blocked
  expect(router.state.location.pathname).toBe('/workflows')
  expect(name.value).toBe('Payroll')

  confirm.mockReturnValue(true)
  const leaving = router.navigate(1)
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(2))
  await leaving
  expect(router.state.location.pathname).toBe('/workflows/4')
})

test('a link is blocked once while a new workflow is unsaved and not after it is created', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'POST' && call.url === '/api/workflows') {
      return json({
        id: 8,
        name: 'Payroll',
        description: null,
        active: true,
        createdAt: '2026-11-17T00:00:00Z',
        stepRevision: 0,
      })
    }
    if (call.url === '/api/workflows/8' && call.method === 'GET') {
      return json({
        id: 8,
        name: 'Payroll',
        description: null,
        active: true,
        createdAt: '2026-11-17T00:00:00Z',
        stepRevision: 0,
      })
    }
    if (call.url === '/api/workflows/8/steps') {
      return json([])
    }
    if (call.url.startsWith('/api/workflows')) {
      return json({
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 20,
        number: 0,
        empty: true,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })
  const confirm = vi.fn(() => false)
  window.confirm = confirm
  renderAt('/workflows')

  const name = await screen.findByLabelText('Name')
  fireEvent.change(name, { target: { value: 'Payroll' } })
  fireEvent.click(screen.getByRole('link', { name: 'Overview' }))
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
  expect(confirm).toHaveBeenCalledWith(UNSAVED_NAVIGATION)
  expect((name as HTMLInputElement).value).toBe('Payroll')
  expect(calls.some((call) => call.method === 'POST')).toBe(false)

  fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }))
  expect(await screen.findByRole('heading', { name: 'Payroll' })).toBeTruthy()
  expect(confirm).toHaveBeenCalledTimes(1)
})

test('refresh is warned only while a step draft is unsaved', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: { ...steps[0], name: 'Customer email' },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })
  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()

  const clean = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(clean)
  expect(clean.defaultPrevented).toBe(false)

  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  const dirty = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(dirty)
  expect(dirty.defaultPrevented).toBe(true)

  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect(await screen.findByRole('heading', { name: 'Customer email' })).toBeTruthy()
  const saved = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(saved)
  expect(saved.defaultPrevented).toBe(false)
})

function leaveBlocked(): boolean {
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  return event.defaultPrevented
}

test('editing a field marks the form dirty', async () => {
  installFetch(() => json({ detail: 'Not found' }, 404))
  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  expect(leaveBlocked()).toBe(false)

  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  expect(leaveBlocked()).toBe(true)
})

test('restoring the original value clears dirty', async () => {
  installFetch(() => json({ detail: 'Not found' }, 404))
  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Call API' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Call API' }))
  const url = document.getElementById('step-url-9') as HTMLInputElement
  fireEvent.change(url, { target: { value: 'https://example.com/orders' } })
  expect(leaveBlocked()).toBe(true)

  fireEvent.change(url, { target: { value: 'https://example.com/hook' } })
  expect(leaveBlocked()).toBe(false)
})

test('restoring several edited fields clears dirty only after all values match', async () => {
  installFetch(() => json({ detail: 'Not found' }, 404))
  const confirm = vi.fn(() => false)
  window.confirm = confirm
  const { router } = renderRouter(['/workflows', '/workflows/4'])
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.change(document.getElementById('step-fields-3') as HTMLTextAreaElement, {
    target: { value: 'email, name' },
  })
  expect(leaveBlocked()).toBe(true)

  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Check fields' },
  })
  expect(leaveBlocked()).toBe(true)
  const blocked = router.navigate(-1)
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
  await blocked
  expect(router.state.location.pathname).toBe('/workflows/4')

  fireEvent.change(document.getElementById('step-fields-3') as HTMLTextAreaElement, {
    target: { value: 'email' },
  })
  expect(leaveBlocked()).toBe(false)
  await router.navigate(-1)
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(router.state.location.pathname).toBe('/workflows')
})

test('saving clears dirty and the saved step becomes the baseline', async () => {
  installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: { ...steps[0], name: 'Customer email' },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })
  const confirm = vi.fn(() => false)
  window.confirm = confirm
  const { router } = renderRouter(['/workflows', '/workflows/4'])
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect(await screen.findByRole('heading', { name: 'Customer email' })).toBeTruthy()
  expect(leaveBlocked()).toBe(false)

  fireEvent.click(screen.getByRole('button', { name: 'Edit Customer email' }))
  const name = document.getElementById('step-name-3') as HTMLInputElement
  fireEvent.change(name, { target: { value: 'Check fields' } })
  expect(leaveBlocked()).toBe(true)
  fireEvent.change(name, { target: { value: 'Customer email' } })
  expect(leaveBlocked()).toBe(false)
  await router.navigate(-1)
  expect(confirm).not.toHaveBeenCalled()
  expect(router.state.location.pathname).toBe('/workflows')
})

test('invalid JSON does not clear dirty', async () => {
  installFetch(() => json({ detail: 'Not found' }, 404))
  renderAt('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Check fields' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Check fields' }))
  const fields = document.getElementById('step-fields-3') as HTMLTextAreaElement
  fireEvent.change(fields, {
    target: { value: '{\n  "requiredFields": ["email"]\n}' },
  })
  expect(leaveBlocked()).toBe(false)

  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Customer email' },
  })
  fireEvent.change(fields, { target: { value: '{' } })
  fireEvent.change(document.getElementById('step-name-3') as HTMLInputElement, {
    target: { value: 'Check fields' },
  })
  expect(leaveBlocked()).toBe(true)
})

test('selecting POST saves JSON types and reloads them', async () => {
  const saved = {
    method: 'POST',
    url: 'https://example.com/orders',
    bodySource: 'STATIC_JSON',
    body: { count: 2, ok: true, label: 'ada', note: null },
  }
  const calls = installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: { ...steps[1], configuration: saved },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Call API' }))
  fireEvent.change(document.getElementById('step-method-9') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  fireEvent.change(document.getElementById('step-url-9') as HTMLInputElement, {
    target: { value: 'https://example.com/orders' },
  })
  fireEvent.change(document.getElementById('step-body-9') as HTMLTextAreaElement, {
    target: { value: '{"count":2,"ok":true,"label":"ada","note":null}' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('POST https://example.com/orders')).toBeTruthy()
  expect(screen.getByText('Static JSON')).toBeTruthy()
  expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
    name: 'Call API',
    configuration: saved,
    revision: 3,
  })

  fireEvent.click(screen.getByRole('button', { name: 'Edit Call API' }))
  expect((document.getElementById('step-body-9') as HTMLTextAreaElement).value).toBe(
    '{"count":2,"ok":true,"label":"ada","note":null}',
  )
})

test('invalid POST JSON stays in the editor until it is corrected', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: {
          ...steps[1],
          configuration: {
            method: 'POST',
            url: 'https://example.com/hook',
            bodySource: 'STATIC_JSON',
            body: 1,
          },
        },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Call API' }))
  fireEvent.change(document.getElementById('step-method-9') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  const body = document.getElementById('step-body-9') as HTMLTextAreaElement
  fireEvent.change(body, { target: { value: '{' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('JSON is invalid')).toBeTruthy()
  expect(calls.some((call) => call.method === 'PATCH')).toBe(false)
  expect(body.value).toBe('{')

  fireEvent.change(body, { target: { value: '1' } })
  expect(screen.queryByText('JSON is invalid')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('POST https://example.com/hook')).toBeTruthy()
  expect(calls.find((call) => call.method === 'PATCH')?.body).toMatchObject({
    configuration: {
      method: 'POST',
      url: 'https://example.com/hook',
      bodySource: 'STATIC_JSON',
      body: 1,
    },
  })
})

test('an unsafe integer stays unsaved when editing a step', async () => {
  const calls = installFetch(() => json({ detail: 'should not be called' }, 500))

  renderAt('/workflows/4')
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Call API' }))
  fireEvent.change(document.getElementById('step-method-9') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  const body = document.getElementById('step-body-9') as HTMLTextAreaElement
  fireEvent.change(body, { target: { value: '{"id":9007199254740993}' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(
    await screen.findByText(
      "Integer exceeds JavaScript's safe range (±9007199254740991). Send it as a JSON string if the receiver accepts that.",
    ),
  ).toBeTruthy()
  expect(calls.some((call) => call.method === 'PATCH')).toBe(false)
  expect(body.value).toBe('{"id":9007199254740993}')
})

test('a decimal that would change stays unsaved when editing a step', async () => {
  const calls = installFetch(() => json({ detail: 'should not be called' }, 500))

  renderAt('/workflows/4')
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Call API' }))
  fireEvent.change(document.getElementById('step-method-9') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  const body = document.getElementById('step-body-9') as HTMLTextAreaElement
  fireEvent.change(body, { target: { value: '{"rate":1.0000000000000001}' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(
    await screen.findByText(
      'Decimal does not survive JSON parsing unchanged. Send it as a JSON string if the receiver accepts that.',
    ),
  ).toBeTruthy()
  expect(calls.some((call) => call.method === 'PATCH')).toBe(false)
  expect(body.value).toBe('{"rate":1.0000000000000001}')
})

test('switching a step back to GET saves the URL only', async () => {
  const calls = installFetch((call) => {
    if (call.method === 'PATCH') {
      return json({
        step: {
          ...steps[1],
          configuration: { method: 'GET', url: 'https://example.com/orders' },
        },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderAt('/workflows/4')
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Call API' }))
  fireEvent.change(document.getElementById('step-method-9') as HTMLSelectElement, {
    target: { value: 'POST' },
  })
  fireEvent.change(document.getElementById('step-body-source-9') as HTMLSelectElement, {
    target: { value: 'WORKFLOW_PAYLOAD' },
  })
  expect(screen.queryByLabelText('JSON body')).toBeNull()
  fireEvent.change(document.getElementById('step-method-9') as HTMLSelectElement, {
    target: { value: 'GET' },
  })
  fireEvent.change(document.getElementById('step-url-9') as HTMLInputElement, {
    target: { value: 'https://example.com/orders' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('GET https://example.com/orders')).toBeTruthy()
  expect(screen.queryByText('Workflow payload')).toBeNull()
  expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
    name: 'Call API',
    configuration: { method: 'GET', url: 'https://example.com/orders' },
    revision: 3,
  })
})
