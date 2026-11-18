export class ApiError extends Error {
  readonly status: number
  readonly title: string | null

  constructor(status: number, message: string, title: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.title = title
  }
}

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
