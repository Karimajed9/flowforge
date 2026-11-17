import { ErrorState } from './ErrorState.tsx'
import { MissingResource } from './MissingResource.tsx'

export function RequestError({
  status,
  message,
  onRetry,
  notFound,
  denied,
}: {
  status: number | null
  message: string
  onRetry?: () => void
  notFound?: { title: string; description: string; to: string; label: string }
  denied: string
}) {
  if (status === 401) {
    return null
  }
  if (status === 403) {
    return <ErrorState title="Access denied" message={denied} />
  }
  if (status === 404 && notFound) {
    return <MissingResource {...notFound} />
  }
  if (status === 0 || status === null || (status >= 500 && status <= 599)) {
    return (
      <ErrorState
        message={
          status !== null && status >= 500
            ? 'The service ran into a problem. Try again in a moment.'
            : message
        }
        onRetry={onRetry}
      />
    )
  }
  return <ErrorState message={message} onRetry={onRetry} />
}
