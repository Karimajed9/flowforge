// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { AppRoutes } from '../App.tsx'

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
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
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
  expect(screen.getByText('Method: GET')).toBeTruthy()
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
