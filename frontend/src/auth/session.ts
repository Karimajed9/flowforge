import { clearCsrf, currentCsrf, refreshCsrf } from './csrf.ts'

export interface AuthProfile {
  subject: string
  issuer: string
  username: string
  permissions: string[]
}

const listeners = new Set<() => void>()
let generation = 0

const credentialMarkers = ['access_token', 'refresh_token', 'id_token', 'code_verifier']

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function isAdmin(profile: AuthProfile): boolean {
  return profile.permissions.includes('flowforge.admin')
}

export function signIn(): void {
  window.location.assign('/api/auth/login')
}

export async function loadProfile(): Promise<AuthProfile | null> {
  removeLegacyOidcStorage()
  const ticket = generation
  let response: Response
  try {
    response = await fetch('/api/auth/me', {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
  } catch {
    return null
  }
  if (ticket !== generation) {
    return null
  }
  if (response.status === 401) {
    clearCsrf()
    return null
  }
  if (!response.ok) {
    return null
  }
  const body: unknown = await response.json()
  const profile = readProfile(body)
  if (ticket !== generation) {
    return null
  }
  if (profile) {
    try {
      await refreshCsrf()
    } catch {
      clearCsrf()
    }
  }
  return profile
}

export function noteSignedOut(): void {
  generation += 1
  clearCsrf()
  emit()
}

export async function signOut(): Promise<void> {
  const csrf = await currentCsrf()
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = '/api/auth/logout'
  const field = document.createElement('input')
  field.type = 'hidden'
  field.name = csrf.parameterName
  field.value = csrf.token
  form.append(field)
  document.body.append(form)
  clearCsrf()
  generation += 1
  form.submit()
}

export function removeLegacyOidcStorage(): void {
  if (typeof window === 'undefined') {
    return
  }
  purge(window.sessionStorage)
  purge(window.localStorage)
}

function emit(): void {
  for (const listener of listeners) {
    listener()
  }
}

function purge(store: Storage): void {
  const keys: string[] = []
  for (let index = 0; index < store.length; index += 1) {
    const key = store.key(index)
    if (key) {
      keys.push(key)
    }
  }
  for (const key of keys) {
    if (!key.startsWith('oidc.')) {
      continue
    }
    const value = store.getItem(key)
    if (key.startsWith('oidc.user:') || holdsCredential(value)) {
      store.removeItem(key)
    }
  }
}

function holdsCredential(value: string | null): boolean {
  if (!value) {
    return false
  }
  return credentialMarkers.some((marker) => value.includes(marker))
}

function readProfile(value: unknown): AuthProfile | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const record = value as Record<string, unknown>
  if (
    'access_token' in record ||
    'refresh_token' in record ||
    'id_token' in record
  ) {
    return null
  }
  if (
    typeof record.subject !== 'string' ||
    record.subject.length === 0 ||
    typeof record.issuer !== 'string' ||
    record.issuer.length === 0 ||
    typeof record.username !== 'string' ||
    !Array.isArray(record.permissions)
  ) {
    return null
  }
  const permissions = record.permissions.filter(
    (permission): permission is string => typeof permission === 'string',
  )
  return {
    subject: record.subject,
    issuer: record.issuer,
    username: record.username,
    permissions,
  }
}
