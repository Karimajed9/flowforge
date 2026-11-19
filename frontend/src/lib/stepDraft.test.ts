import { expect, test } from 'vitest'
import { stepDraftDiffers } from './stepDraft.ts'
import type { WorkflowStep } from '../types/workflow.ts'

const validation: WorkflowStep = {
  id: 3,
  name: 'Check fields',
  stepType: 'VALIDATE_FIELDS',
  position: 1,
  configuration: { requiredFields: ['email'] },
}

const request: WorkflowStep = {
  id: 9,
  name: 'Call API',
  stepType: 'HTTP_REQUEST',
  position: 2,
  configuration: { url: 'https://example.com/hook', method: 'GET' },
}

const condition: WorkflowStep = {
  id: 10,
  name: 'Amount is 10',
  stepType: 'CONDITION',
  position: 1,
  configuration: {
    field: 'amount',
    operator: 'EQUALS',
    value: 10,
    trueSteps: [12],
    falseSteps: [13],
  },
}

test('names, types, and parsed configuration use the saved baseline', () => {
  expect(
    stepDraftDiffers(validation, {
      name: ' Check fields ',
      stepType: 'VALIDATE_FIELDS',
      configurationText: 'email',
    }),
  ).toBe(false)
  expect(
    stepDraftDiffers(validation, {
      name: 'Check fields',
      stepType: 'HTTP_REQUEST',
      configurationText: 'email',
    }),
  ).toBe(true)
  expect(
    stepDraftDiffers(request, {
      name: 'Call API',
      stepType: 'HTTP_REQUEST',
      configurationText: 'https://example.com/hook',
    }),
  ).toBe(false)
  expect(
    stepDraftDiffers(validation, {
      name: 'Check fields',
      stepType: 'VALIDATE_FIELDS',
      configurationText: '{ "requiredFields": ["email"] }',
    }),
  ).toBe(false)
  expect(
    stepDraftDiffers(validation, {
      name: 'Check fields',
      stepType: 'VALIDATE_FIELDS',
      configurationText: '{',
    }),
  ).toBe(true)
  expect(
    stepDraftDiffers(condition, {
      name: 'Amount is 10',
      stepType: 'CONDITION',
      configurationText: JSON.stringify(condition.configuration),
    }),
  ).toBe(false)
  expect(
    stepDraftDiffers(condition, {
      name: 'Amount is 10',
      stepType: 'CONDITION',
      configurationText: JSON.stringify({ ...condition.configuration, value: '10' }),
    }),
  ).toBe(true)
  expect(
    stepDraftDiffers(condition, {
      name: 'Amount is 10',
      stepType: 'CONDITION',
      configurationText: '{',
    }),
  ).toBe(true)
})
