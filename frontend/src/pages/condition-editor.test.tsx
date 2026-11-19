// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { renderApp } from '../test/renderApp.tsx'
import { STALE_ELSEWHERE } from '../components/editorFeedback.ts'
import { CONDITION_MESSAGES } from '../lib/condition.ts'

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

const workflow = {
  id: 4,
  name: 'Onboarding',
  description: 'New hire',
  active: true,
  createdAt: '2026-11-16T15:00:00Z',
  stepRevision: 3,
}

function notify(position = 2) {
  return {
    id: 12,
    name: 'Notify',
    stepType: 'VALIDATE_FIELDS',
    position,
    configuration: { requiredFields: ['email'] },
  }
}

function record(position = 3) {
  return {
    id: 13,
    name: 'Record',
    stepType: 'HTTP_REQUEST',
    position,
    configuration: { method: 'GET', url: 'https://example.com/hook' },
  }
}

function amount(trueSteps: number[], falseSteps: number[], value: unknown = 10, operator = 'EQUALS') {
  return {
    id: 10,
    name: 'Amount is 10',
    stepType: 'CONDITION',
    position: 1,
    configuration: { field: 'amount', operator, value, trueSteps, falseSteps },
  }
}

function installFetch(
  steps: unknown[],
  handler: (call: Call) => Response | Promise<Response> = () => json({ detail: 'Not found' }, 404),
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

function requestBody(calls: Call[], method: string): Record<string, unknown> {
  const call = calls.find((item) => item.method === method)
  if (!call || !call.body || typeof call.body !== 'object') {
    throw new Error(`Missing ${method} body`)
  }
  return call.body as Record<string, unknown>
}

function leaveBlocked(): boolean {
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  return event.defaultPrevented
}

function setValue(id: string, value: string) {
  fireEvent.change(document.getElementById(id) as HTMLInputElement, {
    target: { value },
  })
}

test('a workflow with no condition still loads its steps', async () => {
  installFetch([
    notify(1),
    record(2),
    {
      id: 11,
      name: 'Confirm',
      stepType: 'VALIDATE_FIELDS',
      position: 3,
      configuration: { requiredFields: ['ready'] },
    },
  ])

  renderApp('/workflows/4')

  expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'Record' })).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'Confirm' })).toBeTruthy()
  expect(screen.queryByText('CONDITION')).toBeNull()
  expect(screen.queryByText('TRUE branch')).toBeNull()
  expect(screen.queryByText('FALSE branch')).toBeNull()
  expect(screen.getByRole('button', { name: 'Add field check' })).toBeTruthy()
})

test('adding a condition saves persisted ids in position order and shows the branches', async () => {
  const calls = installFetch([record(), notify()], (call) => {
    if (call.method === 'POST') {
      const body = call.body as {
        name: string
        position: number
        configuration: Record<string, unknown>
      }
      return json({
        id: 30,
        name: body.name,
        stepType: 'CONDITION',
        position: body.position,
        configuration: body.configuration,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Add to FALSE: Record (position 3)' }))
  fireEvent.click(screen.getByRole('button', { name: 'Add to TRUE: Notify (position 2)' }))
  setValue('condition-step-name', 'Amount is 10')
  setValue('new-condition-field', 'amount')
  setValue('new-condition-value-kind', 'number')
  setValue('new-condition-value', '10')
  fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))

  expect(await screen.findByRole('heading', { name: 'Amount is 10' })).toBeTruthy()
  expect(screen.getByText('CONDITION')).toBeTruthy()
  expect(screen.getByText('TRUE branch')).toBeTruthy()
  expect(screen.getByText('FALSE branch')).toBeTruthy()
  expect(screen.getAllByText('Notify').length).toBeGreaterThan(1)
  const saved = requestBody(calls, 'POST')
  expect(saved).toEqual({
    name: 'Amount is 10',
    stepType: 'CONDITION',
    position: 2,
    configuration: {
      field: 'amount',
      operator: 'EQUALS',
      value: 10,
      trueSteps: [12],
      falseSteps: [13],
    },
  })
  const savedConfiguration = saved.configuration as { value: unknown }
  expect(typeof savedConfiguration.value).toBe('number')
})

test('a condition is inserted before occupied branch steps without asking for a gap', async () => {
  const calls = installFetch([notify(1), record(2)], (call) => {
    if (call.method === 'POST') {
      const body = call.body as {
        name: string
        position: number
        configuration: Record<string, unknown>
      }
      return json({
        id: 30,
        name: body.name,
        stepType: 'CONDITION',
        position: body.position,
        configuration: body.configuration,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Add to TRUE: Notify (position 1)' }))
  fireEvent.click(screen.getByRole('button', { name: 'Add to FALSE: Record (position 2)' }))
  setValue('condition-step-name', 'Amount is 10')
  setValue('new-condition-field', 'amount')
  setValue('new-condition-value-kind', 'number')
  setValue('new-condition-value', '10')
  fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))

  expect(await screen.findByRole('heading', { name: 'Amount is 10' })).toBeTruthy()
  expect(screen.queryByText(/open position immediately before/)).toBeNull()
  expect(screen.getByText('Position 1')).toBeTruthy()
  expect(screen.getAllByText('Position 2').length).toBeGreaterThan(0)
  expect(screen.getByText('Position 3')).toBeTruthy()
  expect(requestBody(calls, 'POST')).toMatchObject({ position: 1 })
  expect(calls.find((call) => call.method === 'POST')?.url).toBe(
    '/api/workflows/4/steps?revision=3',
  )
})

test('a string, boolean, null, and EXISTS value keep their JSON types', async () => {
  const cases: Array<{
    kind: string
    value: string
    operator?: string
    expected: unknown
    present: boolean
  }> = [
    { kind: 'string', value: '10', expected: '10', present: true },
    { kind: 'boolean', value: 'true', expected: true, present: true },
    { kind: 'null', value: '', expected: null, present: true },
    { kind: 'string', value: '', operator: 'EXISTS', expected: undefined, present: false },
  ]

  for (const item of cases) {
    cleanup()
    const calls = installFetch([notify()], (call) => {
      if (call.method === 'POST') {
        const body = call.body as {
          name: string
          position: number
          configuration: Record<string, unknown>
        }
        return json({
          id: 30,
          name: body.name,
          stepType: 'CONDITION',
          position: body.position,
          configuration: body.configuration,
        })
      }
      return json({ detail: 'Not found' }, 404)
    })
    renderApp('/workflows/4')
    expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Add to FALSE: Notify (position 2)' }))
    setValue('condition-step-name', 'Amount is 10')
    setValue('new-condition-field', 'amount')
    if (item.operator) {
      setValue('new-condition-operator', item.operator)
    } else {
      setValue('new-condition-value-kind', item.kind)
      if (item.kind === 'string') {
        setValue('new-condition-value', item.value)
      }
      if (item.kind === 'boolean') {
        setValue('new-condition-bool', item.value)
      }
    }
    fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))
    await waitFor(() => expect(calls.some((call) => call.method === 'POST')).toBe(true))
    const configuration = requestBody(calls, 'POST').configuration as Record<string, unknown>
    expect(Object.hasOwn(configuration, 'value')).toBe(item.present)
    if (item.present) {
      expect(configuration.value).toBe(item.expected)
    }
    expect(configuration.falseSteps).toEqual([12])
  }
})

test('invalid configuration is shown and not saved', async () => {
  const calls = installFetch([notify(), record()])
  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()

  setValue('condition-step-name', 'Amount is 10')
  setValue('new-condition-field', 'amount')
  setValue('new-condition-value-kind', 'number')
  setValue('new-condition-value', '10')
  fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))
  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    CONDITION_MESSAGES.branchRequired,
  )

  fireEvent.click(screen.getByRole('button', { name: 'Add to TRUE: Notify (position 2)' }))
  setValue('new-condition-field', 'amount.total')
  fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', CONDITION_MESSAGES.field)

  setValue('new-condition-field', 'amount')
  setValue('new-condition-operator', 'GREATER_THAN')
  setValue('new-condition-value', 'ten')
  fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))
  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    CONDITION_MESSAGES.valueNumber,
  )

  fireEvent.click(screen.getByRole('button', { name: 'Remove from TRUE: Notify (position 2)' }))
  fireEvent.click(screen.getByRole('button', { name: 'Add to TRUE: Record (position 3)' }))
  fireEvent.click(screen.getByRole('button', { name: 'Add to FALSE: Notify (position 2)' }))
  setValue('new-condition-operator', 'EQUALS')
  setValue('new-condition-value-kind', 'number')
  setValue('new-condition-value', '10')
  fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))
  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    CONDITION_MESSAGES.trueBeforeFalse,
  )
  expect(calls.some((call) => call.method === 'POST')).toBe(false)
})

test('a loaded condition shows labeled branches and an edit keeps the other steps', async () => {
  const calls = installFetch([amount([12], [13]), notify(), record()], (call) => {
    if (call.method === 'PATCH') {
      const body = call.body as { name: string; configuration: Record<string, unknown> }
      return json({
        step: {
          ...amount([12], [13]),
          name: body.name,
          configuration: body.configuration,
        },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Amount is 10' })).toBeTruthy()
  expect(screen.getByText('CONDITION')).toBeTruthy()
  expect(screen.getByText('EQUALS amount')).toBeTruthy()
  expect(screen.getByText('Value 10')).toBeTruthy()
  expect(screen.getByText('TRUE branch')).toBeTruthy()
  expect(screen.getByText('FALSE branch')).toBeTruthy()
  expect(screen.getByText('Required fields: email')).toBeTruthy()
  expect(leaveBlocked()).toBe(false)

  fireEvent.click(screen.getByRole('button', { name: 'Edit Amount is 10' }))
  expect(leaveBlocked()).toBe(false)
  setValue('step-condition-10-field', 'total')
  expect(leaveBlocked()).toBe(true)
  setValue('step-condition-10-field', 'amount')
  expect(leaveBlocked()).toBe(false)
  setValue('step-condition-10-value', '11')
  expect(leaveBlocked()).toBe(true)
  setValue('step-condition-10-value', '10')
  expect(leaveBlocked()).toBe(false)
  setValue('step-condition-10-value-kind', 'string')
  expect(leaveBlocked()).toBe(true)
  setValue('step-condition-10-value-kind', 'number')
  expect(leaveBlocked()).toBe(false)

  setValue('step-condition-10-field', 'total')
  setValue('step-condition-10-operator', 'GREATER_THAN')
  setValue('step-condition-10-value', '11')
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(calls.some((call) => call.method === 'PATCH')).toBe(true))
  expect(calls.find((call) => call.method === 'PATCH')?.url).toBe('/api/workflows/4/steps/10')
  expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
    name: 'Amount is 10',
    revision: 3,
    configuration: {
      field: 'total',
      operator: 'GREATER_THAN',
      value: 11,
      trueSteps: [12],
      falseSteps: [13],
    },
  })
  expect(screen.getByText('Required fields: email')).toBeTruthy()
  expect(screen.getByText('GET https://example.com/hook')).toBeTruthy()
})

test('editing a string comparison does not turn it into a number', async () => {
  const calls = installFetch([amount([12], [], '10'), notify()], (call) => {
    if (call.method === 'PATCH') {
      const body = call.body as { configuration: Record<string, unknown> }
      return json({
        step: { ...amount([12], [], '10'), configuration: body.configuration },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByText('Value "10"')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Amount is 10' }))
  setValue('step-condition-10-value', '11')
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(calls.some((call) => call.method === 'PATCH')).toBe(true))
  const configuration = requestBody(calls, 'PATCH').configuration as {
    value: unknown
    trueSteps: number[]
    falseSteps: number[]
  }
  expect(configuration.value).toBe('11')
  expect(configuration.trueSteps).toEqual([12])
  expect(configuration.falseSteps).toEqual([])
})

test('a saved step can be added to either branch when the order stays contiguous', async () => {
  const calls = installFetch(
    [amount([12], []), notify(), record()],
    (call) => {
      if (call.method === 'PATCH') {
        const body = call.body as { configuration: Record<string, unknown> }
        return json({
          step: { ...amount([12], []), configuration: body.configuration },
          stepRevision: 4,
        })
      }
      return json({ detail: 'Not found' }, 404)
    },
  )

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Amount is 10' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Amount is 10' }))
  expect(screen.getByText(/cannot be saved with the current API/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Add Record (position 3) to the true branch' }))
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(calls.some((call) => call.method === 'PATCH')).toBe(true))
  const added = requestBody(calls, 'PATCH').configuration as { trueSteps: number[] }
  expect(added.trueSteps).toEqual([12, 13])
})

test('a following step is added to the false branch and is not offered to the true branch', async () => {
  const finish = {
    id: 15,
    name: 'Finish',
    stepType: 'VALIDATE_FIELDS',
    position: 4,
    configuration: { requiredFields: ['ready'] },
  }
  const calls = installFetch([amount([12], [13]), notify(), record(), finish], (call) => {
    if (call.method === 'PATCH') {
      const body = call.body as { configuration: Record<string, unknown> }
      return json({
        step: { ...amount([12], [13]), configuration: body.configuration },
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Finish' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Amount is 10' }))
  expect(screen.queryByRole('button', { name: 'Add Finish (position 4) to the true branch' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Add Finish (position 4) to the false branch' }))
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(calls.some((call) => call.method === 'PATCH')).toBe(true))
  expect(calls.find((call) => call.method === 'PATCH')?.body).toMatchObject({
    configuration: { trueSteps: [12], falseSteps: [13, 15] },
  })
})

test('deleting a branch step is blocked and deleting the condition keeps the branch steps', async () => {
  const calls = installFetch([amount([12], [13]), notify(), record()], (call) => {
    if (call.method === 'DELETE') {
      return json({ steps: [notify(), record()], stepRevision: 4 })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Notify' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Delete Notify' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', CONDITION_MESSAGES.delete)
  expect(screen.queryByRole('button', { name: 'Confirm delete' })).toBeNull()
  expect(calls.some((call) => call.method === 'DELETE')).toBe(false)

  fireEvent.click(screen.getByRole('button', { name: 'Keep step' }))
  fireEvent.click(screen.getByRole('button', { name: 'Delete Amount is 10' }))
  expect(screen.getByText(/Runs that already started keep the steps/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }))

  await waitFor(() => expect(calls.some((call) => call.method === 'DELETE')).toBe(true))
  expect(calls.find((call) => call.method === 'DELETE')?.url).toBe(
    '/api/workflows/4/steps/10?revision=3',
  )
  await waitFor(() => expect(screen.queryByRole('heading', { name: 'Amount is 10' })).toBeNull())
  expect(screen.getByRole('heading', { name: 'Notify' })).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'Record' })).toBeTruthy()
})

test('reordering inside a branch is saved and a move that mixes branches is blocked', async () => {
  const audit = {
    id: 14,
    name: 'Audit',
    stepType: 'VALIDATE_FIELDS',
    position: 3,
    configuration: { requiredFields: ['ready'] },
  }
  const steps = [amount([12, 14], [13]), notify(), audit, { ...record(), position: 4 }]
  const calls = installFetch(steps, (call) => {
    if (call.method === 'PUT') {
      return json({
        steps: [
          amount([14, 12], [13]),
          { ...audit, position: 2 },
          { ...notify(), position: 3 },
          { ...record(), position: 4 },
        ],
        stepRevision: 4,
      })
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Audit' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Move Audit down' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', CONDITION_MESSAGES.move)
  expect(calls.some((call) => call.method === 'PUT')).toBe(false)

  fireEvent.click(screen.getByRole('button', { name: 'Move Audit up' }))
  await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true))
  expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({
    stepIds: [10, 14, 12, 13],
    revision: 3,
  })
  await waitFor(() => {
    const headings = screen.getAllByRole('heading', { level: 3 }).map((node) => node.textContent)
    expect(headings.indexOf('Audit')).toBeLessThan(headings.indexOf('Notify'))
  })
})

test('a new step cannot be created inside a condition branch', async () => {
  const calls = installFetch([amount([12], [13]), notify(), { ...record(), position: 4 }])
  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Record' })).toBeTruthy()
  setValue('field-step-name', 'Middle')
  setValue('field-step-position', '3')
  setValue('field-step-fields', 'email')
  fireEvent.click(screen.getByRole('button', { name: 'Add field check' }))
  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'Creating a step at that position would split the condition branch.',
  )
  expect(calls.some((call) => call.method === 'POST')).toBe(false)
})

test('a condition save uses the stale-edit message when the conflict has no reason', async () => {
  installFetch([amount([12], [13]), notify(), record()], (call) => {
    if (call.method === 'PATCH') {
      return json({ timestamp: '2026-11-17T00:00:00Z', status: 409, error: 'Conflict' }, 409)
    }
    return json({ detail: 'Not found' }, 404)
  })

  renderApp('/workflows/4')
  expect(await screen.findByRole('heading', { name: 'Amount is 10' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Amount is 10' }))
  setValue('step-name-10', 'Amount check')
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', STALE_ELSEWHERE)
})

test('execution history shows the taken branch and skipped steps', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/executions/21') {
      return Promise.resolve(
        json({
          id: 21,
          workflowId: 4,
          status: 'SUCCEEDED',
          createdAt: '2026-11-16T15:04:00Z',
          failureReason: null,
        }),
      )
    }
    if (url === '/api/executions/21/steps') {
      return Promise.resolve(
        json([
          {
            stepId: 13,
            name: 'Record',
            stepType: 'HTTP_REQUEST',
            position: 3,
            status: 'SKIPPED',
            attemptNumber: 1,
            failureReason: null,
            startedAt: '2026-11-16T15:04:02Z',
            updatedAt: '2026-11-16T15:04:02Z',
            leaseUntil: null,
            branchTaken: null,
          },
          {
            stepId: 10,
            name: 'Amount is 10',
            stepType: 'CONDITION',
            position: 1,
            status: 'SUCCEEDED',
            attemptNumber: 1,
            failureReason: null,
            startedAt: '2026-11-16T15:04:01Z',
            updatedAt: '2026-11-16T15:04:02Z',
            leaseUntil: null,
            branchTaken: true,
          },
          {
            stepId: 12,
            name: 'Notify',
            stepType: 'VALIDATE_FIELDS',
            position: 2,
            status: 'SUCCEEDED',
            attemptNumber: 1,
            failureReason: null,
            startedAt: '2026-11-16T15:04:02Z',
            updatedAt: '2026-11-16T15:04:03Z',
            leaseUntil: null,
            branchTaken: null,
          },
        ]),
      )
    }
    return Promise.resolve(json({ detail: 'Not found' }, 404))
  })

  renderApp('/executions/21')
  expect(await screen.findByRole('heading', { name: 'Amount is 10' })).toBeTruthy()
  expect(screen.getByText('Took the true branch')).toBeTruthy()
  expect(screen.getByText('SKIPPED')).toBeTruthy()
  expect(screen.getByText('This step was skipped.')).toBeTruthy()
  expect(screen.getByText('Notify')).toBeTruthy()
})

test('a false decision is shown on the condition', async () => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/executions/22') {
      return Promise.resolve(
        json({
          id: 22,
          workflowId: 4,
          status: 'SUCCEEDED',
          createdAt: '2026-11-16T15:04:00Z',
          failureReason: null,
        }),
      )
    }
    return Promise.resolve(
      json([
        {
          stepId: 10,
          name: 'Amount is 10',
          stepType: 'CONDITION',
          position: 1,
          status: 'SUCCEEDED',
          attemptNumber: 1,
          failureReason: null,
          startedAt: null,
          updatedAt: null,
          leaseUntil: null,
          branchTaken: false,
        },
      ]),
    )
  })

  renderApp('/executions/22')
  expect(await screen.findByText('Took the false branch')).toBeTruthy()
})
