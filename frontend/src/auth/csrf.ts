export interface CsrfToken {
  headerName: string
  parameterName: string
  token: string
}

let cached: CsrfToken | null = null

export function clearCsrf(): void {
  cached = null
}

export async function refreshCsrf(): Promise<CsrfToken> {
  cached = null
  return readCsrf()
}

export async function csrfHeaders(): Promise<Record<string, string>> {
  const csrf = await readCsrf()
  return { [csrf.headerName]: csrf.token }
}

export async function currentCsrf(): Promise<CsrfToken> {
  return readCsrf()
}

async function readCsrf(): Promise<CsrfToken> {
  if (cached) {
    return cached
  }
  const response = await fetch('/api/auth/csrf', {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    throw new Error('The security token could not be read')
  }
  const body: unknown = await response.json()
  if (!isCsrfToken(body)) {
    throw new Error('The security token could not be read')
  }
  cached = body
  return body
}

function isCsrfToken(value: unknown): value is CsrfToken {
  if (!value || typeof value !== 'object') {
    return false
  }
  const record = value as Record<string, unknown>
  return (
    typeof record.headerName === 'string' &&
    record.headerName.length > 0 &&
    typeof record.parameterName === 'string' &&
    record.parameterName.length > 0 &&
    typeof record.token === 'string' &&
    record.token.length > 0 &&
    !('access_token' in record) &&
    !('refresh_token' in record) &&
    !('id_token' in record)
  )
}
