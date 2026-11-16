export function parseName(
  raw: string,
): { ok: true; name: string } | { ok: false; message: string } {
  const name = raw.trim()
  if (name.length === 0) {
    return { ok: false, message: 'Name is required' }
  }
  if (name.length > 150) {
    return { ok: false, message: 'Name must be 150 characters or fewer' }
  }
  return { ok: true, name }
}

export function parsePosition(
  raw: string,
): { ok: true; position: number } | { ok: false; message: string } {
  const value = raw.trim()
  if (!/^[1-9]\d*$/.test(value)) {
    return { ok: false, message: 'Position must be 1 or greater' }
  }
  const position = Number(value)
  if (!Number.isSafeInteger(position)) {
    return { ok: false, message: 'Position must be 1 or greater' }
  }
  return { ok: true, position }
}

export function parseRequiredFieldNames(
  raw: string,
): { ok: true; names: string[] } | { ok: false; message: string } {
  const names = raw
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
  if (names.length === 0) {
    return {
      ok: false,
      message: 'At least one required field name is needed',
    }
  }
  return { ok: true, names }
}

export function validateHttpMethod(method: string): string | null {
  if (method !== 'GET') {
    return 'Method must be GET'
  }
  return null
}

export function validateHttpsUrl(raw: string): string | null {
  const url = raw.trim()
  if (url.length === 0 || url.length > 2048) {
    return 'A valid HTTPS URL is required'
  }

  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return 'Invalid URL'
  }

  if (parsed.protocol !== 'https:' || parsed.hostname.length === 0) {
    return 'A valid HTTPS URL is required'
  }
  if (parsed.username.length > 0 || parsed.password.length > 0 || parsed.hash.length > 0) {
    return 'A valid HTTPS URL is required'
  }
  if (parsed.port.length > 0) {
    const port = Number(parsed.port)
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      return 'A valid HTTPS URL is required'
    }
  }

  let host = parsed.hostname.toLowerCase()
  if (host.endsWith('.')) {
    host = host.slice(0, -1)
  }
  if (
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host === 'local' ||
    host.endsWith('.local') ||
    host === 'internal' ||
    host.endsWith('.internal')
  ) {
    return 'Local or internal hostnames are not allowed'
  }
  if (
    host.startsWith('[') ||
    host.includes(':') ||
    /^[0-9.]+$/.test(host) ||
    host.startsWith('0x')
  ) {
    return 'Direct IP addresses are not allowed'
  }
  if (!host.includes('.') || host.startsWith('.') || host.endsWith('.')) {
    return 'A valid public hostname is required'
  }
  return null
}

export function buildHttpRequestConfiguration(
  method: string,
  rawUrl: string,
):
  | { ok: true; configuration: { method: 'GET'; url: string } }
  | { ok: false; message: string } {
  const methodError = validateHttpMethod(method)
  if (methodError) {
    return { ok: false, message: methodError }
  }
  const urlError = validateHttpsUrl(rawUrl)
  if (urlError) {
    return { ok: false, message: urlError }
  }
  return { ok: true, configuration: { method: 'GET', url: rawUrl.trim() } }
}
