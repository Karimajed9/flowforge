import type { WorkflowStep } from '../types/workflow.ts'
import { buildHttpRequestConfiguration, parseRequiredFieldNames } from './workflowForm.ts'

export type StepDraft = {
  name: string
  stepType: WorkflowStep['stepType']
  configurationText: string
}

export function configurationText(step: WorkflowStep): string {
  if (step.stepType === 'VALIDATE_FIELDS') {
    const fields = Array.isArray(step.configuration.requiredFields)
      ? step.configuration.requiredFields.filter(
          (field): field is string => typeof field === 'string',
        )
      : []
    return fields.join(', ')
  }
  return typeof step.configuration.url === 'string' ? step.configuration.url : ''
}

export function stepDraftDiffers(step: WorkflowStep, draft: StepDraft): boolean {
  if (draft.stepType !== step.stepType) {
    return true
  }
  if (draft.name.trim() !== step.name.trim()) {
    return true
  }
  const current = configurationFromText(draft.stepType, draft.configurationText)
  if (!current.ok) {
    return true
  }
  return !matchesSavedConfiguration(current.configuration, step)
}

function matchesSavedConfiguration(
  current: Record<string, unknown>,
  step: WorkflowStep,
): boolean {
  if (sameJson(current, step.configuration)) {
    return true
  }
  const projected = configurationFromText(step.stepType, configurationText(step))
  return projected.ok && sameJson(current, projected.configuration)
}

function configurationFromText(
  stepType: WorkflowStep['stepType'],
  text: string,
): { ok: true; configuration: Record<string, unknown> } | { ok: false } {
  const trimmed = text.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed) as unknown
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { ok: false }
      }
      return { ok: true, configuration: parsed as Record<string, unknown> }
    } catch {
      return { ok: false }
    }
  }
  if (stepType === 'VALIDATE_FIELDS') {
    const parsed = parseRequiredFieldNames(trimmed)
    if (!parsed.ok) {
      return { ok: false }
    }
    return { ok: true, configuration: { requiredFields: parsed.names } }
  }
  const parsed = buildHttpRequestConfiguration('GET', trimmed)
  if (!parsed.ok) {
    return { ok: false }
  }
  return { ok: true, configuration: parsed.configuration }
}

function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(canonicalize(left)) === JSON.stringify(canonicalize(right))
}

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(canonicalize)
  }
  if (value && typeof value === 'object') {
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      sorted[key] = canonicalize((value as Record<string, unknown>)[key])
    }
    return sorted
  }
  return value
}
