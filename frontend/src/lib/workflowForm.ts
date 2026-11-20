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

export type HttpMethodName = 'GET' | 'POST'

export type HttpBodySource = 'STATIC_JSON' | 'WORKFLOW_PAYLOAD'

export function validateHttpMethod(method: string): string | null {
  if (method !== 'GET' && method !== 'POST') {
    return 'Method must be GET or POST'
  }
  return null
}

const SAFE_INTEGER_LIMIT = '9007199254740991'

const UNSAFE_INTEGER_MESSAGE =
  "Integer exceeds JavaScript's safe range (±9007199254740991). Send it as a JSON string if the receiver accepts that."

const JSON_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/

export function parseJsonValue(
  raw: string,
): { ok: true; value: unknown } | { ok: false; message: string } {
  const text = raw.trim()
  if (text.length === 0) {
    return { ok: false, message: 'JSON is required' }
  }
  let value: unknown
  try {
    value = JSON.parse(text) as unknown
  } catch {
    return { ok: false, message: 'JSON is invalid' }
  }
  // JSON.parse rounds integers outside the safe range. Reject the original
  // literal before that rounded value can be saved.
  if (containsUnsafeInteger(text)) {
    return { ok: false, message: UNSAFE_INTEGER_MESSAGE }
  }
  return { ok: true, value }
}

function containsUnsafeInteger(text: string): boolean {
  let index = 0
  while (index < text.length) {
    const character = text[index]
    if (character === '"') {
      index = endOfString(text, index)
      continue
    }
    if (character === '-' || (character >= '0' && character <= '9')) {
      const token = JSON_NUMBER.exec(text.slice(index))
      if (token && unsafeIntegerLiteral(token[0])) {
        return true
      }
      index += token && token[0].length > 0 ? token[0].length : 1
      continue
    }
    index += 1
  }
  return false
}

function endOfString(text: string, open: number): number {
  let index = open + 1
  while (index < text.length) {
    if (text[index] === '\\') {
      index += 2
      continue
    }
    if (text[index] === '"') {
      return index + 1
    }
    index += 1
  }
  return text.length
}

function unsafeIntegerLiteral(token: string): boolean {
  const match = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token)
  if (!match) {
    return false
  }
  const intPart = match[2]
  const fracPart = match[3] ?? ''
  const digits = intPart + fracPart
  if (/^0+$/.test(digits)) {
    return false
  }
  const exponent = readExponent(match[4])
  if (exponent === 'huge+') {
    return true
  }
  if (exponent === 'huge-' || exponent === 'bad') {
    return false
  }
  const point = intPart.length + exponent
  if (point <= 0) {
    return false
  }
  if (point < digits.length && !/^0*$/.test(digits.slice(point))) {
    return false
  }
  let start = 0
  while (start < point && start < digits.length && digits[start] === '0') {
    start += 1
  }
  if (start >= point) {
    return false
  }
  const length = point - start
  if (length > SAFE_INTEGER_LIMIT.length) {
    return true
  }
  if (length < SAFE_INTEGER_LIMIT.length) {
    return false
  }
  let compared = ''
  for (let offset = 0; offset < length; offset += 1) {
    const digitIndex = start + offset
    compared += digitIndex < digits.length ? digits[digitIndex] : '0'
  }
  return compared > SAFE_INTEGER_LIMIT
}

function readExponent(raw: string | undefined): number | 'huge+' | 'huge-' | 'bad' {
  if (raw === undefined) {
    return 0
  }
  const match = /^([+-]?)(\d+)$/.exec(raw)
  if (!match) {
    return 'bad'
  }
  const digits = match[2].replace(/^0+/, '')
  if (digits.length === 0) {
    return 0
  }
  if (digits.length > 15) {
    return match[1] === '-' ? 'huge-' : 'huge+'
  }
  return (match[1] === '-' ? -1 : 1) * Number(digits)
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
  body?: { source: HttpBodySource; text: string },
):
  | {
      ok: true
      configuration:
        | { method: 'GET'; url: string }
        | { method: 'POST'; url: string; bodySource: 'WORKFLOW_PAYLOAD' }
        | { method: 'POST'; url: string; bodySource: 'STATIC_JSON'; body: unknown }
    }
  | { ok: false; message: string } {
  const methodError = validateHttpMethod(method)
  if (methodError) {
    return { ok: false, message: methodError }
  }
  const urlError = validateHttpsUrl(rawUrl)
  if (urlError) {
    return { ok: false, message: urlError }
  }
  const url = rawUrl.trim()
  if (method === 'GET') {
    return { ok: true, configuration: { method: 'GET', url } }
  }
  const source = body?.source ?? 'STATIC_JSON'
  if (source === 'WORKFLOW_PAYLOAD') {
    return {
      ok: true,
      configuration: { method: 'POST', url, bodySource: 'WORKFLOW_PAYLOAD' },
    }
  }
  const parsed = parseJsonValue(body?.text ?? '')
  if (!parsed.ok) {
    return parsed
  }
  return {
    ok: true,
    configuration: {
      method: 'POST',
      url,
      bodySource: 'STATIC_JSON',
      body: parsed.value,
    },
  }
}

export type HttpDraft = {
  method: HttpMethodName
  url: string
  bodySource: HttpBodySource
  bodyText: string
}

export function httpDraftFrom(configuration: Record<string, unknown>): HttpDraft {
  const method = configuration.method === 'POST' ? 'POST' : 'GET'
  const url = typeof configuration.url === 'string' ? configuration.url : ''
  const bodySource =
    configuration.bodySource === 'WORKFLOW_PAYLOAD' ? 'WORKFLOW_PAYLOAD' : 'STATIC_JSON'
  const bodyText =
    method === 'POST' && bodySource === 'STATIC_JSON' && Object.hasOwn(configuration, 'body')
      ? JSON.stringify(configuration.body)
      : ''
  return { method, url, bodySource, bodyText }
}

export function httpDraftDiffers(
  configuration: Record<string, unknown>,
  draft: HttpDraft,
): boolean {
  const built = buildHttpRequestConfiguration(draft.method, draft.url, {
    source: draft.bodySource,
    text: draft.bodyText,
  })
  if (!built.ok) {
    return true
  }
  return stableJson(built.configuration) !== stableJson(configuration)
}

function stableJson(value: unknown): string {
  return JSON.stringify(sortKeys(value))
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys)
  }
  if (value && typeof value === 'object') {
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      sorted[key] = sortKeys((value as Record<string, unknown>)[key])
    }
    return sorted
  }
  return value
}
